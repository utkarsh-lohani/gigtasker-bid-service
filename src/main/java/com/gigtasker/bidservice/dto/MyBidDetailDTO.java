package com.gigtasker.bidservice.dto;

import com.gigtasker.bidservice.entity.Bid;
import com.gigtasker.bidservice.enums.BidStatus;
import com.gigtasker.bidservice.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A "rich" Data Transfer Object (DTO) sent to the frontend.
 * It combines data from the Bid (this service) with data
 * from the Task (from the task-service) to provide a complete
 * view for the "My Bids" page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyBidDetailDTO {

    private Long bidId;
    private Double amount;
    private String proposal;
    private BidStatus bidStatus;

    private Long taskId;
    private String taskTitle;
    private TaskStatus taskStatus;

    public static MyBidDetailDTO fromEntity(Bid bid, TaskStatus taskStatus, String taskTitle) {
        return  MyBidDetailDTO.builder()
                .bidId(bid.getId())
                .amount(bid.getAmount())
                .proposal(bid.getProposal())
                .bidStatus(bid.getStatus())
                .taskId(bid.getTaskId())
                .taskTitle(taskTitle)
                .taskStatus(taskStatus)
                .build();
    }
}
