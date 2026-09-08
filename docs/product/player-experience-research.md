# Player Experience Research: 0.3.0

## Goal and Limits

The user clarified that "live" means making Black Blast a substantially better game, not hosting or publishing it. This pass uses controlled local experiments and real Android interactions. "200 times better" is an ambition, not a measured claim. No competitor playtest, player interview, retention study or published-research review has been completed; earlier external reference fetches were blocked by network policy.

Preserve the original offline Android puzzle, existing saves, scoring, unlocks, and player agency. No ads, artificial waiting, scarcity, streak pressure, account creation or publication.

## Priorities and Experiments

| Player problem / hypothesis | Experiment | Result | Product change |
|---|---|---|---|
| A quick final drag may be ignored or use an older destination | Move a square to a new cell and release before a Compose frame; compare occupied cells | Before: zero moves. After: correct destination; ordinary and out-of-board drag checks also passed | Absolute pointer tracking; recompute destination at release |
| Same-cell finger motion needlessly recomposes the game | 30 alternating pointer moves inside one destination cell; count Compose changes | Before: 30. After: 0. This does not measure frame presentation or hardware FPS | Raw pointer state read during drawing; derived destination cell observed in composition |
| Players cannot compare scoring consequences before dropping | Forecast crossing clears, combos, rotations and score saturation; compare with independent geometry/formula and actual placement | Core forecast suite and original rule suite passed; real UI preview showed 110 points before awarding exactly 110 | Pure forecast shared with placement; point and line preview in existing score row |
| A rejected drop provides too little explanation | Drag across an occupied cell and release | Labeled blocked state visible; original run retained | Coral collision outline plus icon/text status, not color alone |
| Pulse is difficult to assess before committing | Hold an armed target, inspect affected area, release | Area preview shown without spending charge; release cleared expected cells and awarded expected points | Held-area preview, cancelable by moving away |

Evidence: [fast-drag before](../../artifacts/test-results/android-20260908-154624.txt), [drag after](../../artifacts/test-results/android-20260908-154843.txt), [recomposition before](../../artifacts/qa/drag-recomposition-before.json), [recomposition after](../../artifacts/qa/drag-recomposition-after.json), [combined feedback checks](../../artifacts/test-results/android-20260908-160536.txt).

## Why These Changes

Reliability is a prerequisite for trust in a spatial puzzle. Previewing a move's consequence is intended to support score comparison and learning. Previewing Pulse is intended to reduce accidental spending. Neither claim proves higher enjoyment or retention: only the interaction behavior has been tested.

The screen still starts in gameplay. Forecasts and the Daily progress bar use the existing score/board layout instead of introducing extra menus. Scores, random hand generation, undo limits and cosmetic milestone thresholds have not been redesigned. `PlacementForecast` is not serialized and never replenishes a hand; old saves remain compatible.

## Next Research Milestones

1. **First-time comprehension:** observe 5 new players, without coaching, attempting first placement, rotation, one clear and Pulse. Record actual confusion, unintended actions and task completion; no inferred success rate.
2. **Real-device responsiveness:** measure the same fast-drag workload on a mid-range and a low-end Android phone in release mode. Capture missed drops, frame distributions, input latency and thermal context. The prior emulator jank finding remains open.
3. **Strategy and replay:** compare voluntary use of free hints versus self-chosen placements across several Daily seeds. Investigate whether forecasts teach useful patterns or simply encourage one greedy strategy before changing difficulty or rewards.
4. **Longer sessions and return:** observe whether players understand their restored board, maintain agency, and choose to return for a new challenge. An automated five-minute test is not evidence of enjoyment.

Only add a new mode, progression system, tutorial or audio layer when an observed player problem supports it. Shipping more features is not the measurement target.

## Verification Status

Targeted before/after and feedback checks above are verified. The final 0.3.0 build, cancellation check, responsive screenshots and broader Android regression results will be recorded after execution. Earlier public-release limitations remain in [the runtime audit](../qa/runtime-audit.md); this pass is not production certification.