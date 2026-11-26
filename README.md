# Tyler Agent

A personal desktop assistant for conversations, food tracking, and weekly workout plans.

[Get started](#get-started) · [Using Tyler](#using-tyler) · [Developer guide](docs/guides/DEVELOPMENT.md)

[0.2.1 release notes](docs/releases/0.2.1.md) — maintenance release introducing the Python agent layer and fixes for potential bugs and issues, with no new user-facing features.

## What you can do

- **Pick up a conversation later.** Tyler saves recent chats and restores them when you reopen the app.
- **Keep a food diary.** Describe a meal, then review your food records and nutrition totals in the calendar.
- **Plan your workouts.** Ask Tyler to create and save a week of exercises, or explore suggestions on the Workouts page.
- **Adjust your schedule.** Select a date, add an exercise, and edit its name, sets, reps, or weight.
- **Share your preferences.** Save a profile to give Tyler context about you and what you want help with.

## Get started

If you have a packaged Windows build, run its `Setup.exe` installer and open Tyler. To build and run the app from this repository, follow the [developer setup guide](docs/guides/DEVELOPMENT.md#run-from-source).

1. Open **Settings** in the side panel.
2. Under **Model providers**, choose the provider Tyler uses for chat (OpenAI, Claude, or DeepSeek), then enter and save that provider's **API key**. Chat needs an internet connection. See [how to get an API key](docs/guides/SETUP.md#7-get-an-api-key); a ChatGPT, Claude, or DeepSeek chat subscription can't be used instead of a key.
3. Optionally fill in your profile and preferences.
4. Open **Chat** and tell Tyler what you would like to do.

You can browse saved records and use the workout calendar without starting a chat.

## Using Tyler

### Chat and food tracking

Describe what you ate, including the amount and date when possible. For example:

> I ate 200 grams of chicken breast and a bowl of rice for lunch today. Please record it.

Tyler saves the amount in the unit you used, in your own word: 1 cup of milk is saved as 1 cup, not 240 ml, and a scoop, a can or a medium fries stays a scoop, a can or a medium. If you give two units, such as "one 2 oz bar", Tyler saves either one. Nutrition values are for that amount.

When you don't give a nutrition value, Tyler estimates it: from the brand's published nutrition for packaged and restaurant food, and from USDA FoodData Central for other food. Tyler doesn't look these values up; the estimate comes from what your provider's model knows of them.

Before saving, Tyler checks the date of each food with your provider's model. Each food is recorded on the day you ate it, and a wrong or missing date is corrected. The nutrition values are Tyler's estimate and are not checked a second time. Nutrition values you state yourself are saved exactly as you gave them.

Tyler normally records the foods of a message in one step, and the foods of a step are saved together: all of them, or none. If the date check cannot be completed, or one of the foods cannot be saved, chat shows an error and nothing from that step is saved.

After such an error, look at the **Calendar** before you send the message again. Now and then Tyler records a message in more than one step, and an earlier step may already be saved. An error that begins "The model could not complete a required check" says which it is: "Some foods from this message may already be saved", or "Please try again" when nothing was saved. Other errors do not say which it is.

The date check makes extra requests to your provider, which it bills like any other use: one for the dates of a message's foods, and one more when a date has to be corrected.

Open **Calendar** on the Chat page and choose a date to see your food records and daily nutrition totals. You can delete one record or all records for that day after confirming the deletion.

Tyler keeps recent conversation history between sessions. Use **Clear conversation** when you want to start fresh.

### Weekly workouts

To create and save a plan through chat, try:

> Create and save a seven-day workout plan starting next Monday. My goal is general fitness, and I want bodyweight exercises.

Open **Workouts** in the side panel, then select a date to see its saved exercises and suggested activity. The weekly plan includes strength, cardio, recovery, and rest days.

On the Workouts page, choose **General fitness**, **Strength**, or **Muscle gain**, and select **Bodyweight** or **Gym** equipment. These choices update the suggestions; use **Add to calendar** to save an exercise you want to keep. A plan created through chat saves its exercises automatically.

To make a change:

1. Choose the date and click **Edit** beside a saved exercise, or use **Add exercise** to enter your own.
2. Enter the exercise name, sets and reps such as `4X12` (four sets of twelve), and weight.
3. Click **Save exercise** to keep the change for your next visit.

New suggestions use weight `0` as a placeholder. Set your training weight when editing. You can also delete a saved exercise after confirming.

### Settings

Update your profile and preferences from **Settings**. Tyler can use your saved profile when responding to you.

Under **Model providers** you can:

- Choose the provider used for chat. The change applies to your next message.
- Save an API key for each provider. Keys are never shown again after saving; a provider without a key is marked **No key**.
- Pick each provider's model from the suggestions, or enter a custom model ID.

| Provider | Default model | Other suggestions |
|---|---|---|
| OpenAI | `gpt-6-sol` | `gpt-6.1-sol`, `gpt-6-luna` |
| Claude | `claude-sonnet-5-5` | `claude-opus-5-5`, `claude-haiku-5-5` |
| DeepSeek | DeepSeek V4.1 Flash (model ID `deepseek-flash`) | None |

The defaults are lower-cost models that suit Tyler's everyday tasks. A model you saved that is no longer suggested, such as `gpt-5.6` or `deepseek-v4-pro`, stays saved and keeps working as a custom model ID. If the provider used for chat has no key, chat tells you which key is missing, for example "Claude API key is empty; chat is unavailable".

## Your data

Your profile, API keys, provider settings, recent chat history, food diary, and saved workouts are stored on your computer. Chat messages and any profile information or tool results used in a conversation are sent to the provider you chose for chat (OpenAI, Anthropic for Claude, or DeepSeek) to produce replies. Each provider bills your API account for that use under its own terms.

Tyler can also read and write text files in its own workspace when requested through chat. See the developer guide for [storage locations and configuration](docs/guides/DEVELOPMENT.md#configuration-and-data-storage).

---

## Building or contributing?

The **[Developer guide](docs/guides/DEVELOPMENT.md)** is a separate page covering setup, architecture, APIs, configuration, testing, release versions, and Windows packaging.
