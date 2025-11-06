package com.gigtasker.bidservice.entity;

import com.gigtasker.bidservice.enums.BidStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bids")
public class Bid {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long taskId;

    private Long bidderUserId;

    private Double amount;

    @Enumerated(EnumType.STRING) @Builder.Default
    private BidStatus status = BidStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String proposal;
}
