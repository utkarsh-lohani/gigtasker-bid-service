# 💸 GigTasker Bid Service

This is the "Bidding Engine" for the GigTasker platform. It's a high-performance, reactive Spring Boot microservice responsible for the entire lifecycle of a bid, from placement to acceptance.

This service is a "middle-man" orchestrator. It sits between the user, the tasks, and the notification system. It handles all business logic for placing, viewing, and accepting bids, and it communicates with other services to get the data it needs.

---

## ✨ Core Responsibilities

* **Place a Bid:** Provides an endpoint for a user to place a bid on a task. This is a complex, secure operation:
    1.  Validates the user's token.
    2.  Calls the `user-service` (via `WebClient`) to get the bidder's internal Postgres ID.
    3.  Calls the `task-service` (via `WebClient`) to verify the task is still "OPEN" and that the bidder is *not* the owner.
    4.  Saves the new `Bid` (with `status: PENDING`) to the `bids` table.
    5.  Publishes a `bid.placed` event to the `bid-exchange` in RabbitMQ.

* **View Bids for a Task:** Provides an endpoint for a *task owner* to view all bids on their gig.
    1.  Validates that the logged-in user is the *owner* of the task (by calling `user-service` and `task-service`).
    2.  Fetches all bids for that `taskId`.
    3.  Makes one **batch call** to the `user-service` (`/api/v1/users/batch`) to "zip" the `bidderUserId` with the bidder's *real name*.
    4.  Returns a "rich" `BidDetailDTO` list to the UI.

* **View "My Bids":** Provides an endpoint for a *bidder* to see all bids they have placed.
    1.  Finds the user's ID via the `/me` endpoint.
    2.  Finds all bids from its own DB associated with that `bidderUserId`.
    3.  Makes one **batch call** to the `task-service` (`/api/v1/tasks/batch`) to "zip" the `taskId` with the task's *title and status*.

* **Accept a Bid (Orchestration):** The most complex operation. It acts as the "boss" for this workflow.
    1.  Validates the user is the task owner.
    2.  **Commands** the `task-service` (via `PUT /api/v1/tasks/{id}/assign`) to set the task's status to `ASSIGNED`.
    3.  **Updates** its own `bids` table: sets the winning bid to `ACCEPTED` and all other pending bids to `REJECTED`.
    4.  **Publishes** two