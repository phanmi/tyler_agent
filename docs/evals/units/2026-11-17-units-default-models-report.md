# Food recording eval report

- **Tyler version:** 0.2.1
- **Run started:** 2026-11-17 00:51 UTC
- **Cases:** 24, each run 3 times with food verification off and on
- **Status:** All 288 chats ran.

Each model's table compares verification off and on over the chats that finished in both modes. `agent-python/evals/README.md` explains every row.

## openai / gpt-6-sol

Prices checked 2026-11-13.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |      72 |      72 |       +0 |
| Chats that failed                      |       0 |       0 |       +0 |
| Chats compared                         |      72 |      72 |       +0 |
| Fully correct chats (%)                |    90.3 |    87.5 |     -2.8 |
| Wrong saves                            |       7 |       9 |       +2 |
| Missed items                           |       0 |       0 |       +0 |
| Median calorie error (%)               |     1.1 |     0.7 |     -0.4 |
| Items saved after a rejection          |       0 |       0 |       +0 |
| Items refused                          |       0 |       0 |       +0 |
| Model calls per chat                   |    3.00 |    4.00 |    +1.00 |
| Input tokens per chat                  |    3048 |    3551 |     +503 |
| Input tokens read from cache per chat  |     132 |     103 |      -29 |
| Input tokens written to cache per chat |    1227 |    1221 |       -5 |
| Output tokens per chat                 |     270 |     362 |      +92 |
| Cost per chat (USD)                    | 0.00917 | 0.01115 | +0.00198 |
| Latency per chat, median (s)           |     4.8 |     6.7 |     +1.8 |
| Latency per chat, 95th percentile (s)  |     6.7 |     8.7 |     +2.0 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                    | Verification | Repeat | Problem                                                        |
|-------------------------|--------------|--------|----------------------------------------------------------------|
| pizza-hut-slices        | off          | 1      | pizza: fat 36 (expected 29.6)                                  |
| pizza-hut-slices        | on           | 1      | pizza: protein 32 (expected 26.0)                              |
| pizza-hut-slices        | off          | 2      | pizza: protein 32 (expected 26.0), fat 36 (expected 29.6)      |
| pizza-hut-slices        | on           | 2      | pizza: protein 32 (expected 26.0), fat 36 (expected 29.6)      |
| pizza-hut-slices        | off          | 3      | pizza: fat 36 (expected 29.6)                                  |
| pizza-hut-slices        | on           | 3      | pizza: protein 32 (expected 26.0), fat 38 (expected 29.6)      |
| scoop-of-protein-powder | off          | 1      | protein powder: calories 120 (expected 91.5)                   |
| scoop-of-protein-powder | on           | 1      | protein powder: calories 120 (expected 91.5)                   |
| scoop-of-protein-powder | off          | 2      | protein powder: calories 120 (expected 91.5)                   |
| scoop-of-protein-powder | on           | 2      | protein powder: calories 120 (expected 91.5)                   |
| scoop-of-protein-powder | off          | 3      | protein powder: calories 120 (expected 91.5)                   |
| scoop-of-protein-powder | on           | 3      | protein powder: calories 120 (expected 91.5)                   |
| ear-of-corn             | on           | 1      | corn: calories 77 (expected 98.9), carbs 17\.1 (expected 21.6) |
| ear-of-corn             | off          | 2      | corn: calories 77 (expected 98.9), carbs 17\.1 (expected 21.6) |
| ear-of-corn             | on           | 2      | corn: calories 77 (expected 98.9), carbs 17\.1 (expected 21.6) |
| ear-of-corn             | on           | 3      | corn: calories 77 (expected 98.9), carbs 17\.1 (expected 21.6) |

## anthropic / claude-sonnet-5-5

Prices checked 2026-11-13.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |      72 |      72 |       +0 |
| Chats that failed                      |       0 |       0 |       +0 |
| Chats compared                         |      72 |      72 |       +0 |
| Fully correct chats (%)                |    94.4 |    94.4 |     +0.0 |
| Wrong saves                            |       4 |       4 |       +0 |
| Missed items                           |       0 |       0 |       +0 |
| Median calorie error (%)               |     1.2 |     1.1 |     -0.1 |
| Items saved after a rejection          |       0 |       0 |       +0 |
| Items refused                          |       0 |       0 |       +0 |
| Model calls per chat                   |    3.00 |    4.00 |    +1.00 |
| Input tokens per chat                  |    6836 |    7898 |    +1062 |
| Input tokens read from cache per chat  |       0 |       0 |       +0 |
| Input tokens written to cache per chat |       0 |       0 |       +0 |
| Output tokens per chat                 |     445 |     774 |     +328 |
| Cost per chat (USD)                    | 0.01813 | 0.02353 | +0.00541 |
| Latency per chat, median (s)           |     5.2 |     8.2 |     +3.0 |
| Latency per chat, 95th percentile (s)  |     7.6 |    12.3 |     +4.6 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                    | Verification | Repeat | Problem                                                         |
|-------------------------|--------------|--------|-----------------------------------------------------------------|
| scoop-of-protein-powder | off          | 1      | protein powder: calories 120 (expected 91.5)                    |
| scoop-of-protein-powder | on           | 1      | protein powder: calories 120 (expected 91.5)                    |
| scoop-of-protein-powder | off          | 2      | protein powder: calories 120 (expected 91.5)                    |
| scoop-of-protein-powder | on           | 2      | protein powder: calories 120 (expected 91.5)                    |
| scoop-of-protein-powder | off          | 3      | protein powder: calories 120 (expected 91.5)                    |
| scoop-of-protein-powder | on           | 3      | protein powder: calories 120 (expected 91.5)                    |
| can-of-tuna             | off          | 1      | tuna: calories 110 (expected 141.9), protein 25 (expected 32.1) |
| can-of-tuna             | on           | 2      | tuna: calories 100 (expected 141.9), protein 22 (expected 32.1) |
