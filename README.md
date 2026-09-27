# Tyler Agent

A personal desktop assistant for conversations, food tracking, and weekly workout plans.

[Get started](#get-started) · [Using Tyler](#using-tyler) · [Developer guide](docs/DEVELOPMENT.md)

## What you can do

- **Pick up a conversation later.** Tyler saves recent chats and restores them when you reopen the app.
- **Keep a food diary.** Describe a meal, then review your food records and nutrition totals in the calendar.
- **Plan your workouts.** Ask Tyler to create and save a week of exercises, or explore suggestions on the Workouts page.
- **Adjust your schedule.** Select a date, add an exercise, and edit its name, sets, reps, or weight.
- **Share your preferences.** Save a profile to give Tyler context about you and what you want help with.

## Get started

If you have a packaged Windows build, run its `Setup.exe` installer and open Tyler. To build and run the app from this repository, follow the [developer setup guide](docs/DEVELOPMENT.md#run-from-source).

1. Open **Settings** in the side panel.
2. Enter and save your **OpenAI API key** to enable chat. Chat needs an internet connection.
3. Optionally fill in your profile and preferences.
4. Open **Chat** and tell Tyler what you would like to do.

You can browse saved records and use the workout calendar without starting a chat.

## Using Tyler

### Chat and food tracking

Describe what you ate, including the amount and date when possible. For example:

> I ate 200 grams of chicken breast and a bowl of rice for lunch today. Please record it.

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

Update your profile, preferences, and API key from **Settings**. Tyler can use your saved profile when responding to you.

## Your data

Your profile, API key, recent chat history, food diary, and saved workouts are stored on your computer. Chat messages and any profile information or tool results used in a conversation are sent to OpenAI to produce replies.

Tyler can also read and write text files in its own workspace when requested through chat. See the developer guide for [storage locations and configuration](docs/DEVELOPMENT.md#configuration-and-data-storage).

---

## Building or contributing?

The **[Developer guide](docs/DEVELOPMENT.md)** is a separate page covering setup, architecture, APIs, configuration, testing, release versions, and Windows packaging.
