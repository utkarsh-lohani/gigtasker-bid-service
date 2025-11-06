package com.gigtasker.bidservice.dto;

import com.gigtasker.bidservice.entity.Bid;
import com.gigtasker.bidservice.enums.BidStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BidDetailDTO {
    private Long bidId;
    private Double amount;
    private String proposal;
    private BidStatus status;
    private Long bidderUserId;
    private String bidderName;

    public static BidDetailDTO fromEntity(Bid bid, String bidderName) {
        return BidDetailDTO.builder()
                .bidId(bid.getId())
                .amount(bid.getAmount())
                .proposal(bid.getProposal())
                .status(bid.getStatus())
                .bidderUserId(bid.getBidderUserId())
                .bidderName(bidderName)
                .build();
    }
}
