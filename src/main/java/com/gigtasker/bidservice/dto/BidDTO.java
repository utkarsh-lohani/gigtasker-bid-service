package com.gigtasker.bidservice.dto;

import com.gigtasker.bidservice.entity.Bid;
import com.gigtasker.bidservice.enums.BidStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BidDTO implements Serializable {
    private Long id;
    private Long taskId;
    private Long bidderUserId;
    private Double amount;
    private BidStatus status;
    private String proposal;

    public static BidDTO fromEntity(Bid bid) {
        return BidDTO.builder()
                .id(bid.getId())
                .taskId(bid.getTaskId())
                .bidderUserId(bid.getBidderUserId())
                .amount(bid.getAmount())
                .status(bid.getStatus())
                .proposal(bid.getProposal())
                .build();
    }
}
