package com.gigtasker.bidservice.service;

import com.gigtasker.bidservice.dto.*;
import com.gigtasker.bidservice.entity.Bid;
import com.gigtasker.bidservice.enums.BidStatus;
import com.gigtasker.bidservice.enums.TaskStatus;
import com.gigtasker.bidservice.exception.IllegalBidException;
import com.gigtasker.bidservice.exception.UnauthorizedAccessException;
import com.gigtasker.bidservice.repository.BidRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BidService {

    private final BidRepository bidRepository;
    private final RabbitTemplate rabbitTemplate;
    private final WebClient.Builder webClientBuilder;

    private BidService self;

    // We will use @Lazy @Autowired setter for self-injection
    // This breaks the "circular dependency" loop during startup.
    @Autowired
    @Lazy
    public void setSelf(BidService self) {
        this.self = self;
    }

    public BidService(BidRepository bidRepository, RabbitTemplate rabbitTemplate, WebClient.Builder webClientBuilder) {
        this.bidRepository = bidRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.webClientBuilder = webClientBuilder;
    }

    public static final String EXCHANGE_NAME = "bid-exchange";
    public static final String BID_PLACED_KEY = "bid.placed";
    public static final String BID_ACCEPTED_KEY = "bid.accepted";
    public static final String BID_REJECTED_KEY = "bid.rejected";
    public static final String AUTHORIZATION = "Authorization";
    public static final String AUTHORIZATION_BEARER = "Bearer ";

    @Transactional
    public Mono<BidDTO> placeBid(BidDTO bidRequest) {
        String token = getAuthToken();

        // These network calls are already non-blocking. Perfect.
        Mono<UserDTO> userMono = getUser(token);
        Mono<TaskDTO> taskMono = getTask(token, bidRequest.getTaskId());

        return Mono.zip(userMono, taskMono)
                .flatMap(tuple -> {
                    // These checks are fast and run on the event loop.
                    UserDTO currentUser = tuple.getT1();
                    TaskDTO task = tuple.getT2();

                    if (!TaskStatus.OPEN.equals(task.getStatus())) {
                        return Mono.error(new IllegalBidException("This task is no longer OPEN."));
                    }
                    if (currentUser.getId().equals(task.getPosterUserId())) {
                        return Mono.error(new IllegalBidException("You cannot bid on your own gig."));
                    }

                    // We wrap our *blocking* code in a "Callable"
                    // and tell it to run on the 'boundedElastic' (I/O) thread pool.
                    return Mono.fromCallable(() -> {
                        Bid newBid = Bid.builder()
                                .taskId(bidRequest.getTaskId())
                                .bidderUserId(currentUser.getId())
                                .amount(bidRequest.getAmount())
                                .proposal(bidRequest.getProposal())
                                .status(BidStatus.PENDING)
                                .build();

                        // This is blocking
                        Bid savedBid = bidRepository.save(newBid);
                        BidDTO savedDto = BidDTO.fromEntity(savedBid);

                        // RabbitTemplate.convertAndSend is also blocking
                        rabbitTemplate.convertAndSend(EXCHANGE_NAME, BID_PLACED_KEY, savedDto);

                        return savedDto; // This is the result of our blocking work
                    }).subscribeOn(Schedulers.boundedElastic()); // <-- The "Bridge"
                });
    }

    @Transactional(readOnly = true)
    public Mono<List<BidDetailDTO>> getBidsForTask(Long taskId) {
        String token = getAuthToken();

        // 1. Network calls are non-blocking.
        return Mono.zip(getUser(token), getTask(token, taskId))
                .flatMap(tuple -> {
                    // 2. Security checks.
                    UserDTO currentUser = tuple.getT1();
                    TaskDTO task = tuple.getT2();
                    if (!currentUser.getId().equals(task.getPosterUserId())) {
                        return Mono.error(new UnauthorizedAccessException("You are not the owner of this task."));
                    }

                    // We wrap our blocking DB call and the "zip" logic
                    // in their own reactive chain on the I/O pool.
                    Mono<List<Bid>> bidsMono = Mono.fromCallable(() ->
                            bidRepository.findByTaskId(taskId)
                    ).subscribeOn(Schedulers.boundedElastic());

                    return bidsMono.flatMap(bids -> {
                        if (bids.isEmpty()) {
                            return Mono.just(List.of());
                        }

                        List<Long> bidderIds = bids.stream().map(Bid::getBidderUserId).distinct().toList();

                        // getBatchUsers is already non-blocking, so this is safe
                        return getBatchUsers(token, bidderIds)
                            .map(userMap ->
                            // NO "return" or "{}", this is now a direct expression
                                bids.stream().map(bid -> {
                                    UserDTO bidder = userMap.get(bid.getBidderUserId());
                                    String bidderName = (bidder != null)  ? bidder.getFirstName() + " " + bidder.getLastName() : "Unknown User";
                                    return BidDetailDTO.fromEntity(bid, bidderName); // Assuming this factory exists
                                }).toList()
                        );
                    });
                });
    }

    @Transactional
    public Mono<Void> acceptBid(Long bidId) {
        String token = getAuthToken();

        // Get the winning bid (blocking, so on I/O pool)
        Mono<Bid> winningBidMono = Mono.fromCallable(() ->
                bidRepository.findById(bidId).orElseThrow(() -> new RuntimeException("Bid not found!"))
        ).subscribeOn(Schedulers.boundedElastic());

        // Get the current user (reactive)
        Mono<UserDTO> userMono = getUser(token);

        // Chain off the winningBid
        return winningBidMono.flatMap(winningBid ->
            // Now we have the bid. Zip it with the current user.
            Mono.zip(Mono.just(winningBid), userMono)).flatMap(tuple -> {

            // Now we have the bid + user. Get the task.
            Bid winningBid = tuple.getT1();
            UserDTO currentUser = tuple.getT2();

            return getTask(token, winningBid.getTaskId()).flatMap(task -> { // <-- 'task' is now in scope for the rest of the chain
                // Security Checks (fast, on event loop)
                if (!currentUser.getId().equals(task.getPosterUserId())) {
                    return Mono.error(new UnauthorizedAccessException("You are not the owner of this task."));
                }
                if (!TaskStatus.OPEN.equals(task.getStatus())) {
                    return Mono.error(new IllegalBidException("This task is no longer OPEN."));
                }

                // 7. Assign the task (reactive)
                return assignTask(token, task.getId()).then(
                    // When assignment is done...
                    // call our *final* transactional method.
                    // 'winningBid' and 'task' are BOTH in scope.
                    self.processAndNotifyBids(winningBid, task, token)
                );
            });
        }).then(); // Final .then() to return Mono<Void> and satisfy the compiler
    }

    @Transactional(readOnly = true)
    public Mono<List<MyBidDetailDTO>> getMyBids() {
        String token = getAuthToken();

        // 1. First, find out who "I" am
        return getUser(token).flatMap(currentUser -> {
            // 2. Now that we have the ID, get all respective bids from the DB
            // This is blocking, so we put it on the I/O pool
            Mono<List<Bid>> myBidsMono = Mono.fromCallable(() -> bidRepository.findByBidderUserId(currentUser.getId())).subscribeOn(Schedulers.boundedElastic());

            return myBidsMono.flatMap(myBids -> {
                if (myBids.isEmpty()) {
                    return Mono.just(List.of());
                }

                // 3. Extract the Task IDs from my bids
                List<Long> taskIds = myBids.stream()
                        .map(Bid::getTaskId)
                        .distinct()
                        .toList();

                // 4. Call the task-service's new /batch endpoint
                // (This returns a Mono<Map<Long, TaskDTO>>)
                return getBatchTasks(token, taskIds)
                    .map(taskMap ->
                    // NO "return" or "{}", this is now a direct expression
                        myBids.stream().map(bid -> {
                            TaskDTO task = taskMap.get(bid.getTaskId());
                            String title = (task != null) ? task.getTitle() : "Task Not Found";
                            TaskStatus status = (task != null) ? task.getStatus() : null;
                            return MyBidDetailDTO.fromEntity(bid, status, title);
                        }).toList()
                    );
            });
        });
    }

    @Transactional
    public Mono<Void> processAndNotifyBids(Bid winningBid, TaskDTO task, String token) {
        // This entire block of blocking code will run on the I/O pool
        return Mono.fromRunnable(() -> {

            // Get all bids for this task
            List<Bid> allBidsForTask = bidRepository.findByTaskId(winningBid.getTaskId());

            // Get all bidder IDs
            List<Long> bidderIds = allBidsForTask.stream()
                .map(Bid::getBidderUserId).distinct().toList();

            // Call user-service (safe to .block() here, we're on the I/O pool)
            Map<Long, UserDTO> userMap = getBatchUsers(token, bidderIds).block();
            if (userMap == null) {
                // We'll just log an error and continue
                log.error("Failed to get bidder details. Notifications will not be sent.");
                userMap = Map.of();
            }

            // Loop and send notifications
            for (Bid bid : allBidsForTask) {
                UserDTO bidder = userMap.get(bid.getBidderUserId());
                // We can only notify users we found
                if (bidder == null) continue;

                BidNotificationDTO.BidNotificationDTOBuilder notification = BidNotificationDTO.builder()
                    .bidId(bid.getId())
                    .amount(bid.getAmount())
                    .bidderUserId(bidder.getId())
                    .bidderName(bidder.getFirstName() + " " + bidder.getLastName())
                    .bidderEmail(bidder.getEmail()) // The Keycloak username
                    .taskId(task.getId())
                    .taskTitle(task.getTitle());

                if (bid.getId().equals(winningBid.getId())) {
                    bid.setStatus(BidStatus.ACCEPTED);
                    rabbitTemplate.convertAndSend(EXCHANGE_NAME, BID_ACCEPTED_KEY, notification.status(BidStatus.ACCEPTED).build());

                } else if (bid.getStatus() == BidStatus.PENDING) {
                    bid.setStatus(BidStatus.REJECTED);
                    rabbitTemplate.convertAndSend(EXCHANGE_NAME, BID_REJECTED_KEY,
                            notification.status(BidStatus.REJECTED).build());
                }

                bidRepository.save(bid); // Save the status change
            }
        }).subscribeOn(Schedulers.boundedElastic()).then(); // .then() returns Mono<Void>
    }

    // --- Helper Methods ---

    private String getAuthToken() {
        return ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
                .getToken().getTokenValue();
    }

    private Mono<UserDTO> getUser(String token) {
        return webClientBuilder.build()
                .get()
                .uri("http://user-service/api/v1/users/me")
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .retrieve()
                .bodyToMono(UserDTO.class);
    }

    private Mono<TaskDTO> getTask(String token, Long taskId) {
        return webClientBuilder.build()
                .get()
                .uri("http://task-service/api/v1/tasks/" + taskId)
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .retrieve()
                .bodyToMono(TaskDTO.class);
    }

    private Mono<Map<Long, UserDTO>> getBatchUsers(String token, List<Long> bidderIds) {
        return webClientBuilder.build()
                .post()
                .uri("http://user-service/api/v1/users/batch")
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .bodyValue(bidderIds)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<UserDTO>>() {})
                .map(users -> users.stream()
                        .collect(Collectors.toMap(UserDTO::getId, user -> user)));
    }

    private Mono<Void> assignTask(String token, Long taskId) {
        return webClientBuilder.build()
                .put()
                .uri("http://task-service/api/v1/tasks/" + taskId + "/assign")
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .retrieve()
                .bodyToMono(Void.class);
    }

    private Mono<Map<Long, TaskDTO>> getBatchTasks(String token, List<Long> taskIds) {
        return webClientBuilder.build()
                .post()
                .uri("http://task-service/api/v1/tasks/batch") // <-- Our new endpoint!
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .bodyValue(taskIds)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<TaskDTO>>() {})
                .map(tasks -> tasks.stream()
                        .collect(Collectors.toMap(TaskDTO::getId, task -> task))); // Convert to Map
    }
}
