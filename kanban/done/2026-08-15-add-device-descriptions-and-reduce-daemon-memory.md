# Task Tree

- [done] Add device descriptions and reduce daemon memory
  - [done] Inspect the current device and daemon paths
  - [done] Define the device-description contract
  - [done] Establish release-binary memory baselines
  - [done] Add durable device descriptions
    - [done] Extend the shared device model
    - [done] Migrate PostgreSQL and in-memory storage
    - [done] Expose authenticated management editing
    - [done] Add `update_device_description`
    - [done] Show descriptions in device listings
    - [done] Publish device-list invalidations
  - [done] Reduce idle daemon memory
    - [done] Select low-memory Native settings
    - [done] Defer operation subsystems until needed
    - [done] Release submitted result payloads
    - [done] Measure the optimized release binary
  - [done] Update durable project guidance
  - [done] Validate shared, server, web, and daemon behavior

# Details

- Every active device has a durable description.
- Descriptions are optional, preserve their supplied text, and have no
  domain-level character limit.
- Existing and newly enrolled devices start with an empty description.
- Management users can view and edit descriptions.
- Use a dedicated authenticated description update route while preserving the
  existing rename route.
- MCP clients can view descriptions through `list_device`.
- MCP clients can edit owned-device descriptions through
  `update_device_description`.
- Return the updated device summary from `update_device_description`.
- Keep daemon release binaries and idle resident memory as small as practical.
- Compare memory using the same release binary scenario before and after the
  optimization.
- The current Linux x64 release binary is 14,253,640 bytes.
- The current connected-idle baseline averages 22,677 KiB RSS and 20,318 KiB
  PSS over 20 samples after warmup.
- The selected Native options are `smallBinary`, Latin-1 strings, allocator
  paging disabled, and the concurrent mark-and-sweep collector.
- The selected options produced a 13,583,720-byte candidate averaging 10,770
  KiB RSS and 8,407 KiB PSS in the same scenario.
- Parse help before creating coroutines or the HTTP client.
- Defer terminal and file-transfer subsystems until their first operation.
- IntelliJ IDEA is running, but this repository is not open in it.
- The implementation and validation route is fully determined.
- Do not start DeviceAsMcp server or middleware services on the local
  workstation; perform remaining service-backed validation remotely.
- The selected Native settings reduced the connected-idle candidate from
  22,677 KiB to 10,770 KiB average RSS in the same benchmark.
- The final CMS Linux x64 release binary is 13,586,520 bytes, down from
  14,253,640 bytes.
- The final help fast path peaked at 6,432 KiB RSS, down from 12,396 KiB.
- Shared JVM tests and all non-integration server tests passed.
- The final server test sources, Wasm frontend, Linux x64 daemon compilation,
  and Linux x64 release link passed.
- Four service-backed server integration tests remain excluded locally because
  the workstation must not run the server or middleware.
- All local DeviceAsMcp containers and processes were stopped and removed.
