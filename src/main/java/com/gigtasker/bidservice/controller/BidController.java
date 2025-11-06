package com.gigtasker.bidservice.controller;

import com.gigtasker.bidservice.dto.BidDTO;
import com.gigtasker.bidservice.dto.BidDetailDTO;
import com.gigtasker.bidservice.service.BidService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bids")
@RequiredArgsConstructor
public class BidController {
    private final BidService bidService;

    @PostMapping
    public Mono<ResponseEntity<BidDTO>> placeBid(@RequestBody BidDTO bidRequest) {
        return bidService.placeBid(bidRequest)
                // ".map()" is how you transform the result inside a Mono
                .map(createdBid -> new ResponseEntity<>(createdBid, HttpStatus.CREATED));
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<List<BidDetailDTO>> getBidsForTask(@PathVariable Long taskId) {
        List<BidDetailDTO> bids = bidService.getBidsForTask(taskId);
        return ResponseEntity.ok(bids);
    }

    @PostMapping("/{bidId}/accept")
    public ResponseEntity<Void> acceptBid(@PathVariable Long bidId) {
        bidService.acceptBid(bidId);
        return ResponseEntity.ok().build(); // Return a 200 OK
    }
}
