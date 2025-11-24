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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

        Mono<UserDTO> userMono = getUser(token);
        Mono<TaskDTO> taskMono = getTask(token, bidRequest.getTaskId());

        return Mono.zip(userMono, taskMono)
                .flatMap(tuple -> {
                    UserDTO currentUser = tuple.getT1();
                    TaskDTO task = tuple.getT2();

                    return validateBid(bidRequest, currentUser, task)
                            .then(checkBidLimit(bidRequest, currentUser, task))
                            .then(saveBidInternal(bidRequest, currentUser));
                });
    }

    private Mono<Void> validateBid(BidDTO bidRequest, UserDTO currentUser, TaskDTO task) {
        return Mono.defer(() -> {
            if (!TaskStatus.OPEN.equals(task.getStatus())) {
                return Mono.error(new IllegalBidException("This task is no longer OPEN."));
            }

            if (currentUser.getId().equals(task.getPosterUserId())) {
                return Mono.error(new IllegalBidException("You cannot bid on your own gig."));
            }

            if (task.getDeadline() != null && LocalDateTime.now().isAfter(task.getDeadline())) {
                return Mono.error(new IllegalBidException("The deadline for this task has passed."));
            }

            BigDecimal amount = BigDecimal.valueOf(bidRequest.getAmount());

            if (task.getMinPay() != null && amount.compareTo(task.getMinPay()) < 0) {
                return Mono.error(new IllegalBidException("Bid amount is lower than the minimum pay of $" + task.getMinPay()));
            }

            if (task.getMaxPay() != null && amount.compareTo(task.getMaxPay()) > 0) {
                return Mono.error(new IllegalBidException("Bid amount is higher than the maximum pay of $" + task.getMaxPay()));
            }

            return Mono.empty();
        });
    }

    private Mono<Void> checkBidLimit(BidDTO bidRequest, UserDTO currentUser, TaskDTO task) {
        return Mono.fromCallable(() ->
                        bidRepository.countByTaskIdAndBidderUserId(bidRequest.getTaskId(), currentUser.getId())
                ).subscribeOn(Schedulers.boundedElastic()).flatMap(count -> {
                    int maxBids = (task.getMaxBidsPerUser() != null) ? task.getMaxBidsPerUser() : 3;

                    if (count >= maxBids) {
                        return Mono.error(new IllegalBidException("You have reached the maximum of " + maxBids + " bids for this task."));
                    }
                    return Mono.empty();
                });
    }

    private Mono<BidDTO> saveBidInternal(BidDTO bidRequest, UserDTO currentUser) {
        return Mono.fromCallable(() -> {
            Bid newBid = Bid.builder()
                    .taskId(bidRequest.getTaskId())
                    .bidderUserId(currentUser.getId())
                    .amount(bidRequest.getAmount())
                    .proposal(bidRequest.getProposal())
                    .status(BidStatus.PENDING)
                    .build();

            Bid savedBid = bidRepository.save(newBid);
            BidDTO savedDto = BidDTO.fromEntity(savedBid);

            rabbitTemplate.convertAndSend(EXCHANGE_NAME, BID_PLACED_KEY, savedDto);

            return savedDto;
        }).subscribeOn(Schedulers.boundedElastic());
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

        return loadBidContext(bidId, token)
                .flatMap(this::validateContext)
                .flatMap(ctx -> processPayment(ctx, token))
                .flatMap(ctx -> finalizeOrder(ctx, token))
                .then();
    }

    // --- Data Gathering (Build the Context) ---
    private Mono<AcceptBidContext> loadBidContext(Long bidId, String token) {
        // A. Get Bid (Blocking -> Reactive)
        Mono<Bid> bidMono = Mono.fromCallable(() ->
                bidRepository.findById(bidId)
                        .orElseThrow(() -> new RuntimeException("Bid not found!"))
        ).subscribeOn(Schedulers.boundedElastic());

        // B. Get User (Reactive)
        Mono<UserDTO> userMono = getUser(token);

        // C. Combine Bid + User, then fetch Task
        return Mono.zip(bidMono, userMono)
                .flatMap(tuple -> {
                    Bid bid = tuple.getT1();
                    UserDTO user = tuple.getT2();
                    return getTask(token, bid.getTaskId())
                            .map(task -> new AcceptBidContext(bid, user, task));
                });
    }

    // --- Business Rule Validation ---
    private Mono<AcceptBidContext> validateContext(AcceptBidContext ctx) {
        if (!ctx.poster().getId().equals(ctx.task().getPosterUserId())) {
            return Mono.error(new UnauthorizedAccessException("You are not the owner of this task."));
        }
        if (TaskStatus.OPEN != ctx.task().getStatus()) {
            return Mono.error(new IllegalBidException("This task is no longer OPEN."));
        }
        return Mono.just(ctx);
    }

    // --- Payment Processing ---
    private Mono<AcceptBidContext> processPayment(AcceptBidContext ctx, String token) {
        BigDecimal amount = BigDecimal.valueOf(ctx.bid().getAmount());

        return getUserUuid(token, ctx.task().getPosterUserId())
                .flatMap(posterUuid -> holdFunds(token, posterUuid, amount, ctx.task().getId()))
                .thenReturn(ctx) // Return context if successful
                .onErrorResume(WebClientResponseException.class, e -> {
                    if (e.getStatusCode().is4xxClientError() || e.getStatusCode().is5xxServerError()) {
                        return Mono.error(new IllegalStateException("Payment Failed: Insufficient funds or wallet error."));
                    }
                    return Mono.error(e);
                });
    }

    // --- Finalize (DB Update & Notify) ---
    private Mono<Void> finalizeOrder(AcceptBidContext ctx, String token) {
        return assignTask(token, ctx.task().getId(), ctx.bid().getBidderUserId())
                .then(Mono.defer(() ->
                        self.processAndNotifyBids(ctx.bid(), ctx.task(), token)
                ));
    }

    // --- Internal Record to hold state ---
    private record AcceptBidContext(Bid bid, UserDTO poster, TaskDTO task) {}

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

    // --- Get UUID for Wallet ---
    private Mono<UUID> getUserUuid(String token, Long userId) {
        return webClientBuilder.build()
                .get()
                .uri("http://user-service/api/v1/users/" + userId)
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .retrieve()
                .bodyToMono(UserDTO.class)
                .map(UserDTO::getKeycloakId); // Requires UserDTO to have 'keycloakId'
    }

    // --- Hold Funds Helper ---
    private Mono<Void> holdFunds(String token, UUID userId, BigDecimal amount, Long taskId) {
        // Inner Record for the Request Body
        record HoldRequest(UUID userId, BigDecimal amount, Long taskId) {}

        return webClientBuilder.build()
                .post()
                .uri("http://wallet-service/api/v1/wallet/hold")
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .bodyValue(new HoldRequest(userId, amount, taskId))
                .retrieve()
                .bodyToMono(Void.class);
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

    private Mono<Void> assignTask(String token, Long taskId, Long winningBidId) {
        return webClientBuilder.build()
                .put()
                .uri("http://task-service/api/v1/tasks/" + taskId + "/assign")
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + token)
                .bodyValue(winningBidId)
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
