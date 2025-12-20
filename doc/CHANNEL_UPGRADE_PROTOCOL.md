# Channel Upgrade Protocol (Proposed Optional Module)

## Problem

Packer must be hardcoded at connection-time. Cannot upgrade to better packer without reconnecting.

## Solution

Channel upgrade protocol using EDN control channel for negotiation.

## Architecture

```
Initial Setup:
- Both sides hardcoded to use EDN packer
- EDN channel established (control plane)

Negotiation Phase:
- Client sends capabilities via EDN: {:type :chsk/packer-capabilities :supported [:edn :transit :msgpack]}
- Server responds with agreement via EDN: {:type :chsk/packer-agreement :chosen-packer :transit}

Upgrade Phase (Parallel):
- New Transit channel opens (data plane)
- Both channels coexist
- Client switches to Transit channel
- Old EDN channel kept for control or closed

Result:
- Upgraded from EDN to Transit (or any agreed packer)
- Zero disruption (parallel channels)
- Backward compatible (EDN always available)
```

## Flow Example

```
T0: Client connects → EDN channel established
T1: Client sends capabilities via EDN
T2: Server responds with agreement via EDN
T3: New Transit channel opens (parallel)
T4: Client switches to Transit channel for data
T5: Old EDN channel closed (or kept for control)
```

## Advantages

1. ✅ **Solves hardcoded packer constraint**: Can negotiate better packer
2. ✅ **Backward compatible**: EDN always available as fallback
3. ✅ **Zero disruption**: Parallel channels = no downtime
4. ✅ **Graceful upgrade**: Old clients stay on EDN, new clients upgrade
5. ✅ **Extensible**: Can add more packers later
6. ✅ **Version tolerance**: Different client/server versions coexist

## Potential Issues

1. **UID/Session continuity**: New channel needs same UID
2. **Message ordering**: If both channels active, need ordering guarantee
3. **Cleanup**: When to close old channel?
4. **Fallback**: What if negotiation fails?

## Implementation

Would be a new module: `sente-lite/channel-upgrade` or `sente-lite/packer-negotiation`

### Module Responsibilities

- Capability exchange protocol (EDN-based)
- Channel creation with agreed packer
- Session/UID continuity
- Graceful fallback if negotiation fails
- Cleanup of old channels

## When to Use

- Performance optimization (upgrade to Transit or MessagePack)
- Version upgrades (negotiate new features)
- Graceful degradation (fallback to EDN if new packer fails)

## Status

Proposed (Phase 0 - Optional, performance optimization)

## Notes

This is an elegant solution to the "packer must be hardcoded" constraint. It leverages the fact that EDN is always available as a control plane to negotiate better packers for the data plane. The parallel channel approach ensures zero disruption during upgrade.
