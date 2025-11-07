package com.gigtasker.bidservice.controller;

import com.gigtasker.bidservice.dto.BidDTO;
import com.gigtasker.bidservice.dto.BidDetailDTO;
import com.gigtasker.bidservice.dto.MyBidDetailDTO;
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
                .map(createdBid -> new ResponseEntity<>(createdBid, HttpStatus.CREATED));
    }

    @GetMapping("/task/{taskId}")
    public Mono<ResponseEntity<List<BidDetailDTO>>> getBidsForTask(@PathVariable Long taskId) {
        return bidService.getBidsForTask(taskId).map(ResponseEntity::ok);
    }

    @PostMapping("/{bidId}/accept")
    public Mono<ResponseEntity<Void>> acceptBid(@PathVariable Long bidId) {
        // .map() is for transforming a value.
        // .thenReturn() is for replacing a "complete" signal.
        return bidService.acceptBid(bidId).thenReturn(ResponseEntity.ok().build());
    }

    @GetMapping("/my-bids")
    public Mono<ResponseEntity<List<MyBidDetailDTO>>> getMyBids() {
        return bidService.getMyBids().map(ResponseEntity::ok);
    }
}
