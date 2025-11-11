package com.gigtasker.bidservice.dto;

import com.gigtasker.bidservice.enums.BidStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BidNotificationDTO {

    // Info about the bid
    private Long bidId;
    private Double amount;
    private BidStatus status; // ACCEPTED or REJECTED

    // Info about the bidder
    private Long bidderUserId;
    private String bidderName;
    private String bidderEmail; // This is the Keycloak "principal" name

    // Info about the task
    private Long taskId;
    private String taskTitle;
}
