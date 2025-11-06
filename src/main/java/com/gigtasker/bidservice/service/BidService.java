package com.gigtasker.bidservice.service;

import com.gigtasker.bidservice.dto.BidDTO;
import com.gigtasker.bidservice.dto.BidDetailDTO;
import com.gigtasker.bidservice.dto.TaskDTO;
import com.gigtasker.bidservice.dto.UserDTO;
import com.gigtasker.bidservice.entity.Bid;
import com.gigtasker.bidservice.enums.BidStatus;
import com.gigtasker.bidservice.enums.TaskStatus;
import com.gigtasker.bidservice.exception.IllegalBidException;
import com.gigtasker.bidservice.exception.UnauthorizedAccessException;
import com.gigtasker.bidservice.repository.BidRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

@Service
@RequiredArgsConstructor
public class BidService {

    private final BidRepository bidRepository;
    private final RabbitTemplate rabbitTemplate;
    private final WebClient.Builder webClientBuilder;

    public static final String EXCHANGE_NAME = "bid-exchange";
    public static final String BID_PLACED_KEY = "bid.placed";
    public static final String BID_ACCEPTED_KEY = "bid.accepted";
    public static final String BID_REJECTED_KEY = "bid.rejected";

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
                            return Mono.just(List.<BidDetailDTO>of());
                        }

                        List<Long> bidderIds = bids.stream().map(Bid::getBidderUserId).distinct().toList();

                        // getBatchUsers is already non-blocking, so this is safe
                        return getBatchUsers(token, bidderIds)
                                .map(userMap -> {
                                    // This "zip" logic is fast (just memory)
                                    return bids.stream().map(bid -> {
                                        UserDTO bidder = userMap.get(bid.getBidderUserId());
                                        String bidderName = (bidder != null)
                                                ? bidder.getFirstName() + " " + bidder.getLastName()
                                                : "Unknown User";

                                        return BidDetailDTO.fromEntity(bid, bidderName);
                                    }).collect(Collectors.toList());
                                });
                    });
                });
    }

    @Transactional
    public Mono<Void> acceptBid(Long bidId) {
        String token = getAuthToken();

        // Get the winning bid (this is blocking, so we put it on the I/O pool)
        Mono<Bid> winningBidMono = Mono.fromCallable(() -> bidRepository.findById(bidId)
                        .orElseThrow(() -> new RuntimeException("Bid not found!"))
        ).subscribeOn(Schedulers.boundedElastic());

        // Get the current user (this is non-blocking)
        Mono<UserDTO> userMono = getUser(token);

        // We'll "flatMap" off the winningBidMono
        return winningBidMono.flatMap(winningBid ->
                // Now we have the bid. Zip it with the user.
                Mono.zip(Mono.just(winningBid), userMono)
        ).flatMap(tuple -> {
            // Now we have the bid AND the user.
            Bid winningBid = tuple.getT1();
            UserDTO currentUser = tuple.getT2();

            // Now, get the task (non-blocking)
            return getTask(token, winningBid.getTaskId())
                    .flatMap(task -> {
                        // Now we have ALL 3. Do security check.
                        if (!currentUser.getId().equals(task.getPosterUserId())) {
                            return Mono.error(new UnauthorizedAccessException("You are not the owner of this task."));
                        }
                        if (!TaskStatus.OPEN.equals(task.getStatus())) {
                            return Mono.error(new IllegalBidException("This task is no longer OPEN."));
                        }
                        // Checks passed. Assign the task (non-blocking).
                        return assignTask(token, task.getId());
                    })
                    .then(
                            // When assignment is done, do the FINAL blocking DB/RabbitMQ work
                            Mono.fromRunnable(() -> {
                                List<Bid> allBidsForTask = bidRepository.findByTaskId(winningBid.getTaskId());
                                for (Bid bid : allBidsForTask) {
                                    if (bid.getId().equals(winningBid.getId())) {
                                        bid.setStatus(BidStatus.ACCEPTED);
                                        bidRepository.save(bid);
                                        rabbitTemplate.convertAndSend(EXCHANGE_NAME, BID_ACCEPTED_KEY, BidDTO.fromEntity(bid));
                                    } else if (bid.getStatus() == BidStatus.PENDING) {
                                        bid.setStatus(BidStatus.REJECTED);
                                        bidRepository.save(bid);
                                        rabbitTemplate.convertAndSend(EXCHANGE_NAME, BID_REJECTED_KEY, BidDTO.fromEntity(bid));
                                    }
                                }
                            }).subscribeOn(Schedulers.boundedElastic())
                    );
            // We add a final .then() to the *entire chain* to
            //  explicitly signal that the final result is Mono<Void>. This is what satisfies the compiler.
        }).then();
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
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(UserDTO.class);
    }

    private Mono<TaskDTO> getTask(String token, Long taskId) {
        return webClientBuilder.build()
                .get()
                .uri("http://task-service/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(TaskDTO.class);
    }

    private Mono<Map<Long, UserDTO>> getBatchUsers(String token, List<Long> bidderIds) {
        return webClientBuilder.build()
                .post()
                .uri("http://user-service/api/v1/users/batch")
                .header("Authorization", "Bearer " + token)
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
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(Void.class);
    }
}
