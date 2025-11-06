package com.gigtasker.bidservice.dto;

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
}
