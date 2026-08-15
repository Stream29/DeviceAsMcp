# Task Tree

- [done] Stream device-list updates to the management frontend
  - [done] Confirm the invalidation transport route
  - [done] Add the cross-instance event path
    - [done] Define the versioned RabbitMQ topology
    - [done] Broadcast empty invalidations to each instance queue
    - [done] Route each invalidation by an internal user header
    - [done] Coalesce updates for local SSE subscribers
  - [done] Expose the management SSE stream
    - [done] Authenticate before starting the event stream
    - [done] Send only `device_list.update` with empty data
    - [done] Keep idle management streams alive
  - [done] Publish authoritative device-list changes
    - [done] Publish enrollment, rename, and revoke changes
    - [done] Publish daemon online and offline changes
    - [done] Keep mutation success independent of event delivery
  - [done] Refresh the Compose device inventory
    - [done] Connect with the existing bearer session
    - [done] Debounce invalidations and refetch devices
    - [done] Reconnect and reconcile periodically
  - [done] Record the durable architecture
  - [done] Validate the complete update flow
    - [done] Test local authenticated event delivery
    - [done] Test RabbitMQ cross-instance propagation
    - [done] Run server, shared, and Wasm checks

# Details

- Publish a detail-free `device_list.update` invalidation after the authoritative device list changes.
- Use RabbitMQ to reach server instances that may hold the user's management SSE connection.
- Let the frontend refetch the authoritative device list over HTTP.
- Use one topic exchange and one non-durable, exclusive, auto-delete event queue per server instance.
- Keep the AMQP body empty and carry the user ID only in an internal message header.
- Let every instance consume the event and notify only matching in-memory subscribers.
- Treat invalidations as transient, duplicate-safe, and lossy.
- Send `{}` as SSE data so browsers dispatch the named event reliably.
- Use an initial invalidation, reconnect refresh, and low-frequency reconciliation to close delivery gaps.
- The authenticated SSE test covers initial, enrollment, rename, online, offline, and revoke invalidations.
- All 42 server tests passed with PostgreSQL, Redis, and RabbitMQ integration tests enabled.
- Two real JVM server processes passed a cross-instance check: the management SSE was held by one instance while enrollment on the other instance produced the second `device_list.update`.
- Shared JVM and Linux x64 tests, Linux daemon compilation, Wasm compilation, and the optimized Wasm browser distribution passed.
- IntelliJ IDEA was running but this repository was not among its open projects, so project-level IDEA inspection was not applicable.
