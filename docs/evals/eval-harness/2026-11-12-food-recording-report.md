# Food recording eval report

- **Tyler version:** 0.2.1
- **Run started:** 2026-11-12 02:38 UTC
- **Cases:** 39, each run 3 times with food verification off and on
- **Status:** All 1872 chats ran.

Each model's table compares verification off and on over the chats that finished in both modes. `agent-python/evals/README.md` explains every row.

## openai / gpt-6-sol

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       0 |       0 |       +0 |
| Chats compared                         |     117 |     117 |       +0 |
| Fully correct chats (%)                |    91.5 |    92.3 |     +0.9 |
| Wrong saves                            |       7 |       6 |       -1 |
| Missed items                           |       3 |       3 |       +0 |
| Median calorie error (%)               |     0.3 |     0.3 |     +0.0 |
| Items saved after a rejection          |       0 |       0 |       +0 |
| Items refused                          |       0 |       0 |       +0 |
| Model calls per chat                   |    2.74 |    3.74 |    +1.00 |
| Input tokens per chat                  |    2228 |    2627 |     +399 |
| Input tokens read from cache per chat  |       0 |       0 |       +0 |
| Input tokens written to cache per chat |     273 |     290 |      +17 |
| Output tokens per chat                 |     214 |     295 |      +81 |
| Cost per chat (USD)                    | 0.00673 | 0.00835 | +0.00162 |
| Latency per chat, median (s)           |     4.2 |     5.8 |     +1.6 |
| Latency per chat, 95th percentile (s)  |     5.4 |     9.2 |     +3.8 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case             | Verification | Repeat | Problem                                                                                                                                        |
|------------------|--------------|--------|------------------------------------------------------------------------------------------------------------------------------------------------|
| pizza-hut-slices | off          | 1      | pizza: fat 36 (expected 29.6)                                                                                                                  |
| pizza-hut-slices | on           | 1      | pizza: fat 38 (expected 29.6)                                                                                                                  |
| pizza-hut-slices | off          | 2      | pizza: fat 36 (expected 29.6)                                                                                                                  |
| pizza-hut-slices | on           | 2      | pizza: fat 38 (expected 29.6)                                                                                                                  |
| pizza-hut-slices | off          | 3      | pizza: fat 38 (expected 29.6)                                                                                                                  |
| pizza-hut-slices | on           | 3      | pizza: fat 40 (expected 29.6)                                                                                                                  |
| snickers-bar     | off          | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar     | on           | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar     | off          | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar     | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar     | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar     | on           | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| mcdonalds-fries  | off          | 1      | fries: unit medium order (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion) |
| some-almonds     | on           | 1      | Missed almond                                                                                                                                  |
| some-almonds     | off          | 2      | Missed almond                                                                                                                                  |
| some-almonds     | off          | 3      | Missed almond                                                                                                                                  |
| some-almonds     | on           | 3      | Missed almond                                                                                                                                  |
| on-monday        | on           | 1      | Missed lentil                                                                                                                                  |
| explicit-date    | off          | 3      | Missed avocado                                                                                                                                 |

## openai / gpt-6.1-sol

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       0 |       0 |       +0 |
| Chats compared                         |     117 |     117 |       +0 |
| Fully correct chats (%)                |    92.3 |    92.3 |     +0.0 |
| Wrong saves                            |       6 |       6 |       +0 |
| Missed items                           |       3 |       3 |       +0 |
| Median calorie error (%)               |     0.3 |     0.3 |     +0.0 |
| Items saved after a rejection          |       0 |       0 |       +0 |
| Items refused                          |       0 |       0 |       +0 |
| Model calls per chat                   |    2.74 |    3.74 |    +1.00 |
| Input tokens per chat                  |    2087 |    2485 |     +398 |
| Input tokens read from cache per chat  |      58 |      86 |      +28 |
| Input tokens written to cache per chat |      60 |      32 |      -28 |
| Output tokens per chat                 |     150 |     202 |      +52 |
| Cost per chat (USD)                    | 0.00559 | 0.00684 | +0.00125 |
| Latency per chat, median (s)           |     5.5 |     7.7 |     +2.2 |
| Latency per chat, 95th percentile (s)  |     6.9 |    10.8 |     +3.9 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case             | Verification | Repeat | Problem                                                                                                                          |
|------------------|--------------|--------|----------------------------------------------------------------------------------------------------------------------------------|
| pizza-hut-slices | off          | 1      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices | on           | 1      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices | off          | 2      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices | on           | 2      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices | off          | 3      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices | on           | 3      | pizza: fat 36 (expected 29.6)                                                                                                    |
| snickers-bar     | off          | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar     | on           | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar     | off          | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar     | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar     | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar     | on           | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| some-almonds     | off          | 1      | Missed almond                                                                                                                    |
| some-almonds     | on           | 1      | Missed almond                                                                                                                    |
| some-almonds     | off          | 2      | Missed almond                                                                                                                    |
| some-almonds     | on           | 2      | Missed almond                                                                                                                    |
| some-almonds     | off          | 3      | Missed almond                                                                                                                    |
| some-almonds     | on           | 3      | Missed almond                                                                                                                    |

## openai / gpt-5.6

This model has no price in the table, so no cost is shown.

| Metric                                 |  Off |   On | Change |
|----------------------------------------|-----:|-----:|-------:|
| Chats run                              |  117 |  117 |     +0 |
| Chats that failed                      |    0 |    0 |     +0 |
| Chats compared                         |  117 |  117 |     +0 |
| Fully correct chats (%)                | 92.3 | 88.9 |   -3.4 |
| Wrong saves                            |    9 |   10 |     +1 |
| Missed items                           |    3 |    6 |     +3 |
| Median calorie error (%)               |  0.3 |  0.3 |   +0.0 |
| Items saved after a rejection          |    0 |    0 |     +0 |
| Items refused                          |    0 |    0 |     +0 |
| Model calls per chat                   | 2.79 | 3.72 |  +0.92 |
| Input tokens per chat                  | 2214 | 2558 |   +344 |
| Input tokens read from cache per chat  |    0 |    0 |     +0 |
| Input tokens written to cache per chat |  107 |  151 |    +45 |
| Output tokens per chat                 |  202 |  284 |    +82 |
| Cost per chat (USD)                    |    - |    - |      - |
| Latency per chat, median (s)           |  4.4 |  6.0 |   +1.6 |
| Latency per chat, 95th percentile (s)  |  6.0 |  8.9 |   +2.9 |
| Model calls that reported no usage     |    0 |    0 |     +0 |

### Chats that failed or were not fully correct

| Case                | Verification | Repeat | Problem                                                                                                                                                                                                                                                        |
|---------------------|--------------|--------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| pizza-hut-slices    | off          | 1      | pizza: fat 36 (expected 29.6)                                                                                                                                                                                                                                  |
| pizza-hut-slices    | on           | 1      | pizza: protein 32 (expected 26.0)                                                                                                                                                                                                                              |
| pizza-hut-slices    | off          | 2      | pizza: fat 38 (expected 29.6)                                                                                                                                                                                                                                  |
| pizza-hut-slices    | on           | 2      | pizza: fat 38 (expected 29.6)                                                                                                                                                                                                                                  |
| pizza-hut-slices    | off          | 3      | pizza: protein 32 (expected 26.0), fat 38 (expected 29.6)                                                                                                                                                                                                      |
| pizza-hut-slices    | on           | 3      | pizza: protein 32 (expected 26.0)                                                                                                                                                                                                                              |
| snickers-bar        | off          | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                               |
| snickers-bar        | on           | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                               |
| snickers-bar        | off          | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                               |
| snickers-bar        | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                               |
| snickers-bar        | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                               |
| snickers-bar        | on           | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                               |
| bowl-of-rice        | on           | 2      | rice: calories 310 (expected 205.4)                                                                                                                                                                                                                            |
| some-almonds        | on           | 1      | Missed almond                                                                                                                                                                                                                                                  |
| some-almonds        | on           | 2      | Missed almond                                                                                                                                                                                                                                                  |
| explicit-date       | on           | 2      | Missed avocado                                                                                                                                                                                                                                                 |
| two-items-household | off          | 1      | bread: amount 1 (expected 2), unit serving (2 slices bread and 2 tbsp peanut butter) (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter |
| two-items-household | on           | 1      | bread: amount 1 (expected 2), unit serving (2 slices bread + 2 tbsp peanut butter) (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 34 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter   |
| two-items-household | off          | 2      | bread: amount 1 (expected 2), unit serving (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter                                           |
| two-items-household | on           | 2      | bread: amount 1 (expected 2), unit serving (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter                                           |
| two-items-household | off          | 3      | bread: amount 1 (expected 2), unit meal (2 slices bread + 2 tbsp peanut butter) (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter      |
| two-items-household | on           | 3      | bread: amount 1 (expected 2), unit serving (2 slices bread + 2 tbsp peanut butter) (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter   |

## anthropic / claude-sonnet-5-5

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       6 |       6 |       +0 |
| Chats compared                         |     111 |     111 |       +0 |
| Fully correct chats (%)                |    93.7 |    90.1 |     -3.6 |
| Wrong saves                            |       7 |      11 |       +4 |
| Missed items                           |       0 |       0 |       +0 |
| Median calorie error (%)               |     0.3 |     0.3 |     +0.0 |
| Items saved after a rejection          |       0 |       2 |       +2 |
| Items refused                          |       0 |       0 |       +0 |
| Model calls per chat                   |    2.89 |    4.01 |    +1.12 |
| Input tokens per chat                  |    5813 |    6838 |    +1025 |
| Input tokens read from cache per chat  |       0 |       0 |       +0 |
| Input tokens written to cache per chat |       0 |       0 |       +0 |
| Output tokens per chat                 |     391 |     742 |     +351 |
| Cost per chat (USD)                    | 0.01554 | 0.02110 | +0.00556 |
| Latency per chat, median (s)           |     4.6 |     7.6 |     +3.0 |
| Latency per chat, 95th percentile (s)  |     7.0 |    17.0 |    +10.0 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                  | Verification | Repeat | Problem                                                                                                                                                                                        |
|-----------------------|--------------|--------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ounces-chicken        | on           | 3      | chicken breast: amount 170 (expected 6), unit g (expected oz)                                                                                                                                  |
| snickers-bar          | off          | 1      | snickers: amount 57 (expected 1), unit g (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                               |
| snickers-bar          | on           | 1      | snickers: amount 57 (expected 1), unit g (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                               |
| snickers-bar          | off          | 2      | snickers: amount 57 (expected 1), unit g (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                               |
| snickers-bar          | on           | 2      | snickers: amount 57 (expected 1), unit g (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                               |
| snickers-bar          | off          | 3      | snickers: amount 57 (expected 1), unit g (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                               |
| snickers-bar          | on           | 3      | snickers: amount 57 (expected 1), unit g (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                               |
| cup-of-milk           | on           | 1      | milk: unit cup (240 ml) (expected cup)                                                                                                                                                         |
| cup-of-milk           | on           | 2      | milk: unit cup (244 ml) (expected cup)                                                                                                                                                         |
| cup-of-milk           | off          | 3      | milk: amount 244 (expected 1), unit ml (expected cup)                                                                                                                                          |
| cup-of-milk           | on           | 3      | milk: amount 244 (expected 1), unit ml (expected cup)                                                                                                                                          |
| question-about-snacks | off          | 1      | Failed: internal: KeyError                                                                                                                                                                     |
| question-about-snacks | on           | 1      | Failed: internal: KeyError                                                                                                                                                                     |
| question-about-snacks | off          | 2      | Failed: internal: KeyError                                                                                                                                                                     |
| question-about-snacks | on           | 2      | Failed: internal: KeyError                                                                                                                                                                     |
| question-about-snacks | off          | 3      | Failed: internal: KeyError                                                                                                                                                                     |
| question-about-snacks | on           | 3      | Failed: internal: KeyError                                                                                                                                                                     |
| plan-not-eaten        | off          | 1      | Failed: internal: KeyError                                                                                                                                                                     |
| plan-not-eaten        | on           | 1      | Failed: internal: KeyError                                                                                                                                                                     |
| plan-not-eaten        | off          | 2      | Failed: internal: KeyError                                                                                                                                                                     |
| plan-not-eaten        | on           | 2      | Failed: internal: KeyError                                                                                                                                                                     |
| plan-not-eaten        | off          | 3      | Failed: internal: KeyError                                                                                                                                                                     |
| plan-not-eaten        | on           | 3      | Failed: internal: KeyError                                                                                                                                                                     |
| this-morning          | off          | 1      | orange juice: amount 240 (expected 1), unit ml (expected cup)                                                                                                                                  |
| this-morning          | off          | 2      | orange juice: amount 240 (expected 1), unit ml (expected cup)                                                                                                                                  |
| this-morning          | on           | 2      | orange juice: amount 240 (expected 1), unit ml (expected cup)                                                                                                                                  |
| this-morning          | off          | 3      | orange juice: unit cup (240 ml) (expected cup)                                                                                                                                                 |
| this-morning          | on           | 3      | orange juice: amount 240 (expected 1), unit ml (expected cup)                                                                                                                                  |
| two-items-household   | on           | 2      | bread: amount 1 (expected 2), unit serving (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8) |
| two-items-household   | on           | 3      | bread: amount 1 (expected 2), unit serving (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), fat 16 (expected 2.2), fiber 6 (expected 3.8)                           |

## anthropic / claude-opus-5-5

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       6 |       6 |       +0 |
| Chats compared                         |     111 |     111 |       +0 |
| Fully correct chats (%)                |    95.5 |    92.8 |     -2.7 |
| Wrong saves                            |       5 |       8 |       +3 |
| Missed items                           |       0 |       0 |       +0 |
| Median calorie error (%)               |     0.3 |     0.4 |     +0.1 |
| Items saved after a rejection          |       0 |       0 |       +0 |
| Items refused                          |       0 |       0 |       +0 |
| Model calls per chat                   |    2.89 |    3.97 |    +1.08 |
| Input tokens per chat                  |    5875 |    6853 |     +978 |
| Input tokens read from cache per chat  |       0 |       0 |       +0 |
| Input tokens written to cache per chat |       0 |       0 |       +0 |
| Output tokens per chat                 |     464 |     741 |     +277 |
| Cost per chat (USD)                    | 0.03278 | 0.04224 | +0.00946 |
| Latency per chat, median (s)           |     5.9 |     9.0 |     +3.1 |
| Latency per chat, 95th percentile (s)  |     8.7 |    15.3 |     +6.6 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                  | Verification | Repeat | Problem                                                                                                                          |
|-----------------------|--------------|--------|----------------------------------------------------------------------------------------------------------------------------------|
| ounces-chicken        | on           | 1      | chicken breast: amount 170 (expected 6), unit g (expected oz)                                                                    |
| ounces-chicken        | off          | 2      | chicken breast: amount 170 (expected 6), unit g (expected oz)                                                                    |
| ounces-chicken        | on           | 3      | chicken breast: amount 170 (expected 6), unit g (expected oz)                                                                    |
| pizza-hut-slices      | off          | 1      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices      | on           | 1      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices      | on           | 2      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices      | off          | 3      | pizza: fat 36 (expected 29.6)                                                                                                    |
| pizza-hut-slices      | on           | 3      | pizza: fat 36 (expected 29.6)                                                                                                    |
| snickers-bar          | on           | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar          | off          | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar          | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar          | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| snickers-bar          | on           | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion) |
| question-about-snacks | off          | 1      | Failed: internal: KeyError                                                                                                       |
| question-about-snacks | on           | 1      | Failed: internal: KeyError                                                                                                       |
| question-about-snacks | off          | 2      | Failed: internal: KeyError                                                                                                       |
| question-about-snacks | on           | 2      | Failed: internal: KeyError                                                                                                       |
| question-about-snacks | off          | 3      | Failed: internal: KeyError                                                                                                       |
| question-about-snacks | on           | 3      | Failed: internal: KeyError                                                                                                       |
| plan-not-eaten        | off          | 1      | Failed: internal: KeyError                                                                                                       |
| plan-not-eaten        | on           | 1      | Failed: internal: KeyError                                                                                                       |
| plan-not-eaten        | off          | 2      | Failed: internal: KeyError                                                                                                       |
| plan-not-eaten        | on           | 2      | Failed: internal: KeyError                                                                                                       |
| plan-not-eaten        | off          | 3      | Failed: internal: KeyError                                                                                                       |
| plan-not-eaten        | on           | 3      | Failed: internal: KeyError                                                                                                       |

## anthropic / claude-haiku-5-5

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       0 |       0 |       +0 |
| Chats compared                         |     117 |     117 |       +0 |
| Fully correct chats (%)                |    94.0 |    89.7 |     -4.3 |
| Wrong saves                            |       7 |       9 |       +2 |
| Missed items                           |       0 |       5 |       +5 |
| Median calorie error (%)               |     0.4 |     0.4 |     +0.0 |
| Items saved after a rejection          |       0 |      11 |      +11 |
| Items refused                          |       0 |       2 |       +2 |
| Model calls per chat                   |    2.79 |    3.98 |    +1.19 |
| Input tokens per chat                  |    5812 |    6883 |    +1070 |
| Input tokens read from cache per chat  |       0 |       0 |       +0 |
| Input tokens written to cache per chat |       0 |       0 |       +0 |
| Output tokens per chat                 |     487 |    1204 |     +717 |
| Cost per chat (USD)                    | 0.00082 | 0.00129 | +0.00047 |
| Latency per chat, median (s)           |     3.5 |     6.1 |     +2.6 |
| Latency per chat, 95th percentile (s)  |     5.2 |    19.1 |    +13.9 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                    | Verification | Repeat | Problem                                                                                                                                                                                                                                                                                                                 |
|-------------------------|--------------|--------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| snickers-bar            | off          | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                                                        |
| snickers-bar            | on           | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                                                        |
| snickers-bar            | off          | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                                                        |
| snickers-bar            | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                                                        |
| snickers-bar            | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                                                        |
| snickers-bar            | on           | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                                                        |
| mcdonalds-fries         | off          | 1      | fries: amount 117 (expected 1), unit g (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                            |
| mcdonalds-fries         | off          | 2      | fries: amount 117 (expected 1), unit g (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                            |
| mcdonalds-fries         | off          | 3      | fries: amount 111 (expected 1), unit g (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                            |
| mcdonalds-fries         | on           | 3      | fries: amount 117 (expected 1), unit g (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion)                                                                                                                                                            |
| half-cup-of-broccoli    | on           | 1      | broccoli: amount 78 (expected 0.5), unit g (expected cup)                                                                                                                                                                                                                                                               |
| two-items-lunch         | on           | 1      | Missed chicken breast                                                                                                                                                                                                                                                                                                   |
| two-items-dinner        | off          | 2      | salmon: fat 14 (expected 18.6)                                                                                                                                                                                                                                                                                          |
| two-items-household     | on           | 1      | bread: calories 348 (expected 161.3), protein 16 (expected 7.9), carbs 34 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); peanut butter: amount 1 (expected 2), unit serving (expected tbsp), calories 360 (expected 191.4), protein 15 (expected 7.1), carbs 35 (expected 7.1), fiber 6 (expected 1.6) |
| two-items-household     | on           | 2      | peanut butter: amount 1 (expected 2), unit serving (expected tbsp), calories 340 (expected 191.4), protein 15 (expected 7.1), carbs 34 (expected 7.1), fiber 5.5 (expected 1.6)                                                                                                                                         |
| two-items-household     | on           | 3      | bread: amount 1 (expected 2), unit serving (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter                                                                                                    |
| unusual-stated-calories | on           | 1      | Missed banana                                                                                                                                                                                                                                                                                                           |
| unusual-stated-calories | on           | 2      | Missed banana                                                                                                                                                                                                                                                                                                           |
| label-calories-stated   | on           | 2      | Missed chicken breast                                                                                                                                                                                                                                                                                                   |

## deepseek / deepseek-flash

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       0 |       3 |       +3 |
| Chats compared                         |     114 |     114 |       +0 |
| Fully correct chats (%)                |    73.7 |    86.8 |    +13.2 |
| Wrong saves                            |      34 |       9 |      -25 |
| Missed items                           |       1 |       8 |       +7 |
| Median calorie error (%)               |     0.3 |     0.3 |     -0.1 |
| Items saved after a rejection          |       0 |      28 |      +28 |
| Items refused                          |       0 |       6 |       +6 |
| Model calls per chat                   |    2.63 |    4.21 |    +1.58 |
| Input tokens per chat                  |    3723 |    4815 |    +1091 |
| Input tokens read from cache per chat  |    3169 |    3796 |     +628 |
| Input tokens written to cache per chat |       0 |       0 |       +0 |
| Output tokens per chat                 |     269 |     439 |     +170 |
| Cost per chat (USD)                    | 0.00051 | 0.00086 | +0.00035 |
| Latency per chat, median (s)           |     3.7 |     5.0 |     +1.4 |
| Latency per chat, 95th percentile (s)  |     4.8 |    11.5 |     +6.7 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                         | Verification | Repeat | Problem                                                                                                                                        |
|------------------------------|--------------|--------|------------------------------------------------------------------------------------------------------------------------------------------------|
| grams-rice                   | off          | 2      | rice: date nothing (expected 2026-11-12)                                                                                                       |
| ounces-chicken               | off          | 1      | chicken breast: date 2026-09-07 (expected 2026-11-12)                                                                                          |
| big-mac                      | off          | 1      | big mac: date 2026-09-07 (expected 2026-11-12)                                                                                                 |
| big-mac                      | off          | 3      | big mac: date 2026-09-07 (expected 2026-11-12)                                                                                                 |
| pizza-hut-slices             | on           | 1      | pizza: protein 34 (expected 26.0), fat 36 (expected 29.6)                                                                                      |
| pizza-hut-slices             | on           | 2      | pizza: protein 36 (expected 26.0)                                                                                                              |
| pizza-hut-slices             | on           | 3      | pizza: protein 32 (expected 26.0)                                                                                                              |
| snickers-bar                 | off          | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar                 | on           | 1      | Failed: internal: KeyError                                                                                                                     |
| snickers-bar                 | off          | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar                 | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar                 | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| snickers-bar                 | on           | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)               |
| mcdonalds-fries              | off          | 3      | fries: unit medium order (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion) |
| corrected-count              | off          | 3      | Saved "Hard-boiled eggs, large", which nothing expected                                                                                        |
| tablespoons-of-peanut-butter | off          | 2      | peanut butter: date 2026-09-07 (expected 2026-11-12)                                                                                           |
| tablespoons-of-peanut-butter | on           | 3      | peanut butter: date nothing (expected 2026-11-12)                                                                                              |
| three-eggs                   | off          | 3      | egg: date nothing (expected 2026-11-12)                                                                                                        |
| half-cup-of-broccoli         | on           | 2      | broccoli: date nothing (expected 2026-11-12)                                                                                                   |
| half-cup-of-broccoli         | off          | 3      | Saved "Broccoli, chopped, boiled", which nothing expected                                                                                      |
| a-banana                     | off          | 2      | banana: date 2025-01-01 (expected 2026-11-12)                                                                                                  |
| a-baked-potato               | on           | 1      | Failed: internal: KeyError                                                                                                                     |
| a-baked-potato               | off          | 2      | potato: date nothing (expected 2026-11-12)                                                                                                     |
| someone-else-ate             | off          | 2      | Saved "Big Mac meal (Big Mac, medium fries, medium Coca-Cola)", which nothing expected                                                         |
| on-monday                    | off          | 3      | Missed lentil                                                                                                                                  |
| this-morning                 | on           | 3      | Failed: internal: KeyError                                                                                                                     |
| explicit-date                | off          | 1      | avocado: date 2025-11-03 (expected 2026-11-03)                                                                                                 |
| explicit-date                | off          | 2      | avocado: date 2025-11-03 (expected 2026-11-03)                                                                                                 |
| explicit-date                | off          | 3      | avocado: date 2025-11-03 (expected 2026-11-03)                                                                                                 |
| two-items-lunch              | off          | 1      | chicken breast: date 2026-09-07 (expected 2026-11-12); rice: date 2026-09-07 (expected 2026-11-12)                                             |
| two-items-lunch              | off          | 2      | chicken breast: date 2026-09-07 (expected 2026-11-12); rice: date 2026-09-07 (expected 2026-11-12)                                             |
| two-items-lunch              | on           | 2      | Missed rice                                                                                                                                    |
| two-items-lunch              | off          | 3      | chicken breast: date 2026-09-07 (expected 2026-11-12); rice: date 2026-09-07 (expected 2026-11-12)                                             |
| two-items-lunch              | on           | 3      | Missed chicken breast                                                                                                                          |
| three-items-breakfast        | on           | 1      | Missed oat                                                                                                                                     |
| three-items-breakfast        | on           | 2      | Missed oat                                                                                                                                     |
| two-items-dinner             | on           | 1      | Missed salmon; Saved "boiled broccoli", which nothing expected                                                                                 |
| two-items-dinner             | on           | 3      | Missed salmon; Saved "boiled broccoli", which nothing expected                                                                                 |
| two-items-household          | off          | 1      | Saved "whole wheat bread", which nothing expected; Saved "smooth peanut butter", which nothing expected                                        |
| two-items-household          | on           | 2      | Missed bread                                                                                                                                   |
| two-items-household          | on           | 3      | Missed bread                                                                                                                                   |
| unusual-stated-calories      | off          | 1      | banana: date 2026-09-07 (expected 2026-11-12)                                                                                                  |
| unusual-stated-calories      | off          | 2      | banana: calories 105 (expected 1900, as the user stated)                                                                                       |
| unusual-stated-calories      | off          | 3      | banana: calories 105 (expected 1900, as the user stated); Saved "Banana", which nothing expected                                               |
| all-values-stated            | off          | 1      | Saved "Protein shake", which nothing expected                                                                                                  |
| recipe-values-stated         | off          | 1      | lasagna: date 2026-09-07 (expected 2026-11-12)                                                                                                 |
| recipe-values-stated         | off          | 2      | lasagna: date 2026-09-07 (expected 2026-11-12)                                                                                                 |
| recipe-values-stated         | off          | 3      | lasagna: date 2026-09-07 (expected 2026-11-12)                                                                                                 |
| label-calories-stated        | off          | 2      | chicken breast: date 2026-09-07 (expected 2026-11-12)                                                                                          |

## deepseek / deepseek-v4-pro

Prices checked 2026-11-11.

| Metric                                 |     Off |      On |   Change |
|----------------------------------------|--------:|--------:|---------:|
| Chats run                              |     117 |     117 |       +0 |
| Chats that failed                      |       6 |      10 |       +4 |
| Chats compared                         |     101 |     101 |       +0 |
| Fully correct chats (%)                |    29.7 |    81.2 |    +51.5 |
| Wrong saves                            |      80 |       8 |      -72 |
| Missed items                           |       4 |      16 |      +12 |
| Median calorie error (%)               |     0.4 |     0.4 |     +0.0 |
| Items saved after a rejection          |       0 |      46 |      +46 |
| Items refused                          |       0 |      38 |      +38 |
| Model calls per chat                   |    2.05 |    5.27 |    +3.22 |
| Input tokens per chat                  |    2921 |    5792 |    +2872 |
| Input tokens read from cache per chat  |    2597 |    4880 |    +2284 |
| Input tokens written to cache per chat |       0 |       0 |       +0 |
| Output tokens per chat                 |     234 |     626 |     +392 |
| Cost per chat (USD)                    | 0.00147 | 0.00390 | +0.00243 |
| Latency per chat, median (s)           |     3.8 |     8.8 |     +5.0 |
| Latency per chat, 95th percentile (s)  |     5.4 |    25.4 |    +20.0 |
| Model calls that reported no usage     |       0 |       0 |       +0 |

### Chats that failed or were not fully correct

| Case                         | Verification | Repeat | Problem                                                                                                                                                                                       |
|------------------------------|--------------|--------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| grams-chicken                | off          | 1      | chicken breast: date 2026-06-10 (expected 2026-11-12)                                                                                                                                         |
| grams-chicken                | off          | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| grams-chicken                | off          | 3      | chicken breast: date 2026-02-26 (expected 2026-11-12)                                                                                                                                         |
| grams-rice                   | off          | 1      | rice: date 2026-06-09 (expected 2026-11-12)                                                                                                                                                   |
| grams-rice                   | off          | 2      | rice: date 2026-06-25 (expected 2026-11-12)                                                                                                                                                   |
| grams-rice                   | off          | 3      | rice: date 2026-06-10 (expected 2026-11-12)                                                                                                                                                   |
| grams-almonds                | off          | 1      | almond: date 2026-06-09 (expected 2026-11-12)                                                                                                                                                 |
| grams-almonds                | off          | 2      | almond: date 2026-01-13 (expected 2026-11-12)                                                                                                                                                 |
| grams-almonds                | off          | 3      | almond: date 2026-06-01 (expected 2026-11-12)                                                                                                                                                 |
| grams-yogurt                 | off          | 3      | yogurt: date 2026-02-26 (expected 2026-11-12)                                                                                                                                                 |
| ounces-chicken               | off          | 1      | chicken breast: date 2026-09-07 (expected 2026-11-12)                                                                                                                                         |
| ounces-chicken               | off          | 2      | chicken breast: date 2026-09-07 (expected 2026-11-12)                                                                                                                                         |
| ounces-chicken               | off          | 3      | chicken breast: date 2026-09-07 (expected 2026-11-12)                                                                                                                                         |
| big-mac                      | off          | 1      | big mac: date 2026-09-07 (expected 2026-11-12)                                                                                                                                                |
| big-mac                      | off          | 2      | big mac: date 2026-01-29 (expected 2026-11-12)                                                                                                                                                |
| big-mac                      | on           | 2      | Missed big mac                                                                                                                                                                                |
| big-mac                      | off          | 3      | big mac: date 2026-03-05 (expected 2026-11-12)                                                                                                                                                |
| big-mac                      | on           | 3      | big mac: fiber 0 (expected 3.5)                                                                                                                                                               |
| pizza-hut-slices             | off          | 1      | pizza: date 2026-12-09 (expected 2026-11-12)                                                                                                                                                  |
| pizza-hut-slices             | off          | 2      | pizza: date 2026-03-26 (expected 2026-11-12)                                                                                                                                                  |
| pizza-hut-slices             | off          | 3      | pizza: date 2026-12-17 (expected 2026-11-12)                                                                                                                                                  |
| snickers-bar                 | off          | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion), date 2026-09-07 (expected 2026-11-12)                       |
| snickers-bar                 | on           | 1      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                              |
| snickers-bar                 | off          | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| snickers-bar                 | on           | 2      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion)                                                              |
| snickers-bar                 | off          | 3      | snickers: amount 2 (expected 1), unit oz (expected bar or piece or item or serving or each or unit or count or whole or portion), date 2026-09-07 (expected 2026-11-12)                       |
| snickers-bar                 | on           | 3      | Missed snickers                                                                                                                                                                               |
| mcdonalds-fries              | off          | 1      | fries: date 2026-09-07 (expected 2026-11-12)                                                                                                                                                  |
| mcdonalds-fries              | on           | 1      | Failed: internal: KeyError                                                                                                                                                                    |
| mcdonalds-fries              | off          | 2      | fries: date 2026-09-07 (expected 2026-11-12)                                                                                                                                                  |
| mcdonalds-fries              | on           | 3      | fries: amount 117 (expected 1), unit g (expected medium or order or medium serving or piece or item or serving or each or unit or count or whole or portion)                                  |
| corrected-count              | off          | 1      | egg: date 2026-10-17 (expected 2026-11-12)                                                                                                                                                    |
| corrected-count              | on           | 1      | Failed: internal: KeyError                                                                                                                                                                    |
| corrected-count              | off          | 2      | egg: date 2026-06-04 (expected 2026-11-12)                                                                                                                                                    |
| corrected-count              | on           | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| corrected-count              | off          | 3      | egg: date 2026-12-23 (expected 2026-11-12)                                                                                                                                                    |
| corrected-amount             | on           | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| corrected-amount             | off          | 3      | rice: date 2026-02-25 (expected 2026-11-12)                                                                                                                                                   |
| corrected-date               | off          | 1      | chicken breast: date 2026-06-07 (expected 2026-11-11)                                                                                                                                         |
| slices-of-bread              | off          | 1      | bread: date 2026-09-07 (expected 2026-11-12)                                                                                                                                                  |
| slices-of-bread              | off          | 2      | bread: date 2026-12-16 (expected 2026-11-12)                                                                                                                                                  |
| slices-of-bread              | off          | 3      | bread: date 2026-02-01 (expected 2026-11-12)                                                                                                                                                  |
| cup-of-milk                  | off          | 1      | milk: date 2026-01-15 (expected 2026-11-12)                                                                                                                                                   |
| cup-of-milk                  | off          | 2      | milk: date 2026-01-27 (expected 2026-11-12)                                                                                                                                                   |
| cup-of-milk                  | off          | 3      | milk: date 2026-06-09 (expected 2026-11-12)                                                                                                                                                   |
| tablespoons-of-peanut-butter | off          | 1      | peanut butter: date 2026-09-07 (expected 2026-11-12)                                                                                                                                          |
| tablespoons-of-peanut-butter | off          | 2      | peanut butter: date 2026-01-28 (expected 2026-11-12)                                                                                                                                          |
| tablespoons-of-peanut-butter | off          | 3      | peanut butter: date 2026-06-30 (expected 2026-11-12)                                                                                                                                          |
| tablespoon-of-olive-oil      | off          | 1      | olive oil: date 2026-09-07 (expected 2026-11-12)                                                                                                                                              |
| tablespoon-of-olive-oil      | off          | 2      | olive oil: date 2026-12-09 (expected 2026-11-12)                                                                                                                                              |
| tablespoon-of-olive-oil      | on           | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| tablespoon-of-olive-oil      | off          | 3      | olive oil: date 2026-02-26 (expected 2026-11-12)                                                                                                                                              |
| three-eggs                   | off          | 1      | egg: date 2026-06-26 (expected 2026-11-12)                                                                                                                                                    |
| three-eggs                   | off          | 2      | egg: date 2026-12-16 (expected 2026-11-12)                                                                                                                                                    |
| three-eggs                   | off          | 3      | egg: date 2026-12-18 (expected 2026-11-12)                                                                                                                                                    |
| half-cup-of-broccoli         | off          | 1      | broccoli: date 2026-03-01 (expected 2026-11-12)                                                                                                                                               |
| half-cup-of-broccoli         | off          | 2      | broccoli: date 2026-09-07 (expected 2026-11-12)                                                                                                                                               |
| half-cup-of-broccoli         | off          | 3      | broccoli: date 2026-06-03 (expected 2026-11-12)                                                                                                                                               |
| bowl-of-rice                 | off          | 2      | rice: date 2026-02-26 (expected 2026-11-12)                                                                                                                                                   |
| bowl-of-rice                 | on           | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| bowl-of-rice                 | off          | 3      | rice: date 2026-06-01 (expected 2026-11-12)                                                                                                                                                   |
| a-banana                     | off          | 2      | banana: date 2026-09-07 (expected 2026-11-12)                                                                                                                                                 |
| a-banana                     | off          | 3      | banana: date 2026-02-03 (expected 2026-11-12)                                                                                                                                                 |
| some-almonds                 | off          | 1      | Missed almond                                                                                                                                                                                 |
| some-almonds                 | on           | 1      | Failed: internal: KeyError                                                                                                                                                                    |
| some-almonds                 | off          | 2      | Missed almond                                                                                                                                                                                 |
| some-almonds                 | on           | 2      | Missed almond                                                                                                                                                                                 |
| some-almonds                 | off          | 3      | Missed almond                                                                                                                                                                                 |
| some-almonds                 | on           | 3      | Missed almond                                                                                                                                                                                 |
| a-baked-potato               | off          | 1      | Missed potato                                                                                                                                                                                 |
| a-baked-potato               | off          | 2      | potato: date 2026-02-25 (expected 2026-11-12)                                                                                                                                                 |
| a-baked-potato               | on           | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| a-baked-potato               | on           | 3      | Missed potato                                                                                                                                                                                 |
| question-about-snacks        | off          | 1      | Failed: internal: KeyError                                                                                                                                                                    |
| plan-not-eaten               | off          | 3      | Failed: internal: KeyError                                                                                                                                                                    |
| yesterday                    | off          | 1      | chicken breast: date 2026-02-14 (expected 2026-11-11)                                                                                                                                         |
| yesterday                    | on           | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| yesterday                    | off          | 3      | chicken breast: date 2026-01-03 (expected 2026-11-11)                                                                                                                                         |
| on-monday                    | off          | 3      | Missed lentil                                                                                                                                                                                 |
| this-morning                 | off          | 2      | orange juice: date 2026-06-13 (expected 2026-11-12)                                                                                                                                           |
| this-morning                 | on           | 2      | Missed orange juice                                                                                                                                                                           |
| this-morning                 | off          | 3      | orange juice: date 2026-03-03 (expected 2026-11-12)                                                                                                                                           |
| explicit-date                | on           | 2      | avocado: date 2025-11-03 (expected 2026-11-03)                                                                                                                                                |
| explicit-date                | off          | 3      | avocado: date 2025-11-03 (expected 2026-11-03)                                                                                                                                                |
| two-items-lunch              | off          | 1      | chicken breast: date 2026-07-11 (expected 2026-11-12); rice: date 2026-07-11 (expected 2026-11-12)                                                                                            |
| two-items-lunch              | off          | 2      | chicken breast: date 2026-06-02 (expected 2026-11-12); rice: date 2026-06-02 (expected 2026-11-12)                                                                                            |
| two-items-lunch              | on           | 2      | Missed rice                                                                                                                                                                                   |
| two-items-lunch              | off          | 3      | chicken breast: date 2026-02-24 (expected 2026-11-12); rice: date 2026-02-24 (expected 2026-11-12)                                                                                            |
| two-items-lunch              | on           | 3      | Missed chicken breast                                                                                                                                                                         |
| three-items-breakfast        | off          | 1      | oat: date 2026-02-26 (expected 2026-11-12); milk: date 2026-02-26 (expected 2026-11-12); almond: date 2026-02-26 (expected 2026-11-12)                                                        |
| three-items-breakfast        | on           | 1      | Missed almond                                                                                                                                                                                 |
| three-items-breakfast        | off          | 2      | oat: date 2026-06-10 (expected 2026-11-12); milk: date 2026-06-10 (expected 2026-11-12); almond: date 2026-06-10 (expected 2026-11-12)                                                        |
| three-items-breakfast        | on           | 2      | Missed oat; Missed almond                                                                                                                                                                     |
| three-items-breakfast        | on           | 3      | Failed: internal: KeyError                                                                                                                                                                    |
| two-items-dinner             | off          | 1      | salmon: date 2026-07-11 (expected 2026-11-12); broccoli: date 2026-07-11 (expected 2026-11-12)                                                                                                |
| two-items-dinner             | on           | 1      | Missed salmon                                                                                                                                                                                 |
| two-items-dinner             | off          | 2      | salmon: date 2026-01-30 (expected 2026-11-12); broccoli: date 2026-01-30 (expected 2026-11-12)                                                                                                |
| two-items-dinner             | off          | 3      | salmon: date 2026-06-01 (expected 2026-11-12); broccoli: date 2026-06-01 (expected 2026-11-12)                                                                                                |
| two-items-dinner             | on           | 3      | Saved "baked salmon", which nothing expected; Saved "boiled broccoli", which nothing expected                                                                                                 |
| two-items-household          | off          | 1      | bread: date 2026-02-04 (expected 2026-11-12); peanut butter: date 2026-02-04 (expected 2026-11-12)                                                                                            |
| two-items-household          | on           | 1      | Missed bread; Missed peanut butter                                                                                                                                                            |
| two-items-household          | off          | 2      | bread: date 2026-01-27 (expected 2026-11-12); peanut butter: date 2026-01-27 (expected 2026-11-12)                                                                                            |
| two-items-household          | on           | 2      | bread: unit serving (expected slice), calories 350 (expected 161.3), protein 15 (expected 7.9), carbs 37 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter |
| two-items-household          | off          | 3      | bread: date 2026-05-12 (expected 2026-11-12); peanut butter: date 2026-05-12 (expected 2026-11-12)                                                                                            |
| two-items-household          | on           | 3      | bread: calories 350 (expected 161.3), protein 16 (expected 7.9), carbs 35 (expected 27.3), fat 18 (expected 2.2), fiber 6 (expected 3.8); Missed peanut butter                                |
| unusual-stated-calories      | off          | 2      | Failed: internal: KeyError                                                                                                                                                                    |
| unusual-stated-calories      | on           | 2      | Missed banana                                                                                                                                                                                 |
| unusual-stated-calories      | off          | 3      | banana: date 2026-01-05 (expected 2026-11-12)                                                                                                                                                 |
| all-values-stated            | off          | 2      | shake: date 2026-09-07 (expected 2026-11-12)                                                                                                                                                  |
| all-values-stated            | off          | 3      | Failed: internal: KeyError                                                                                                                                                                    |
| recipe-values-stated         | off          | 1      | lasagna: date 2026-02-27 (expected 2026-11-12)                                                                                                                                                |
| recipe-values-stated         | off          | 2      | lasagna: date 2026-02-11 (expected 2026-11-12)                                                                                                                                                |
| recipe-values-stated         | off          | 3      | lasagna: date 2026-01-26 (expected 2026-11-12)                                                                                                                                                |
| label-calories-stated        | off          | 1      | chicken breast: date 2026-02-10 (expected 2026-11-12)                                                                                                                                         |
| label-calories-stated        | off          | 3      | chicken breast: date 2026-06-09 (expected 2026-11-12)                                                                                                                                         |
