package com.gigtasker.bidservice.service;

import com.gigtasker.bidservice.dto.BidDTO;
import com.gigtasker.bidservice.dto.TaskDTO;
import com.gigtasker.bidservice.dto.UserDTO;
import com.gigtasker.bidservice.entity.Bid;
import com.gigtasker.bidservice.enums.BidStatus;
import com.gigtasker.bidservice.exception.IllegalBidException;
import com.gigtasker.bidservice.exception.UnauthorizedAccessException;
import com.gigtasker.bidservice.repository.BidRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BidService {

    private final BidRepository bidRepository;
    private final RabbitTemplate rabbitTemplate;
    private final WebClient.Builder webClientBuilder; // Injected from WebClientConfig

    public static final String EXCHANGE_NAME = "bid-exchange";
    public static final String ROUTING_KEY = "bid.placed";

    @Transactional
    public BidDTO placeBid(BidDTO bidRequest) {
        // 1. Get the current user's raw token
        JwtAuthenticationToken authentication = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        String token = authentication.getToken().getTokenValue();

        // 2. Fetch BOTH the user and the task *at the same time*
        Mono<UserDTO> userMono = getUser(token);
        Mono<TaskDTO> taskMono = getTask(token, bidRequest.getTaskId());

        // 3. Wait for both calls to complete
        UserDTO currentUser = userMono.block();
        TaskDTO task = taskMono.block();

        if (currentUser == null) throw new UsernameNotFoundException("Could not find user profile!");
        if (task == null) throw new TaskRejectedException("Could not find task!");

        // 4. Compare the IDs.
        if (currentUser.getId().equals(task.getPosterUserId())) {
            throw new IllegalBidException("You cannot bid on your own gig.");
        }

        Bid newBid = Bid.builder()
                .taskId(bidRequest.getTaskId())
                .bidderUserId(currentUser.getId())
                .amount(bidRequest.getAmount())
                .proposal(bidRequest.getProposal())
                .status(BidStatus.PENDING)
                .build();

        Bid savedBid = bidRepository.save(newBid);
        BidDTO savedDto = BidDTO.fromEntity(savedBid);

        rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING_KEY, savedDto);
        return savedDto;
    }

    @Transactional(readOnly = true)
    public List<BidDTO> getBidsForTask(Long taskId) {
        // 1. Get the token
        JwtAuthenticationToken authentication = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        String token = authentication.getToken().getTokenValue();

        // 2. Fetch the user and the task *at the same time*
        Mono<UserDTO> userMono = getUser(token);
        Mono<TaskDTO> taskMono = getTask(token, taskId);

        // 3. Wait for both to finish
        UserDTO currentUser = userMono.block();
        TaskDTO task = taskMono.block();

        if (currentUser == null || task == null) {
            throw new RuntimeException("Could not find user or task.");
        }

        // 4. *** THE SECURITY CHECK ***
        if (!currentUser.getId().equals(task.getPosterUserId())) {
            throw new UnauthorizedAccessException("You are not the owner of this task.");
        }

        // 5. You are the owner. Proceed.
        return bidRepository.findByTaskId(taskId)
                .stream()
                .map(BidDTO::fromEntity) // Convert to DTOs
                .collect(Collectors.toList());
    }

    // --- HELPER METHODS FOR SERVICE CALLS ---
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
                .uri("http://task-service/api/v1/tasks/" + taskId) // Call the endpoint we built
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(TaskDTO.class);
    }
}
