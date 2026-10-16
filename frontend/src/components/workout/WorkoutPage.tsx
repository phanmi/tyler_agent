import { useEffect, useRef, useState } from 'react'
import Calendar from 'react-calendar'
import 'react-calendar/dist/Calendar.css'
import { loadWorkoutPlan } from '../../api'
import type { WorkoutPlan } from '../../types'
import ConfirmDialog from '../common/ConfirmDialog'
import { toDateString, useWorkoutDay } from './useWorkoutDay'

function weekStart(date: Date): string {
  const monday = new Date(date)
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))
  return toDateString(monday)
}

export default function WorkoutPage() {
  const { selectedDate, dateLabel, chooseDate, workouts, loaded, loading, loadError, saving, error, notice,
    draft, editingId, changeDraft, resetForm, edit, save, requestDelete, pendingDelete, confirmDelete,
    cancelDelete, schedule, reload } = useWorkoutDay()
  const [goal, setGoal] = useState('general_fitness')
  const [equipment, setEquipment] = useState('bodyweight')
  const [planState, setPlanState] = useState<{ key: string; plan: WorkoutPlan | null; loading: boolean; error: string }>({
    key: '', plan: null, loading: true, error: '',
  })
  const planRequestId = useRef(0)
  const startDate = weekStart(selectedDate)
  const planKey = JSON.stringify([startDate, goal, equipment])
  const plan = planState.key === planKey ? planState.plan : null
  const planLoading = planState.key !== planKey || planState.loading
  const planError = planState.key === planKey ? planState.error : ''

  useEffect(() => {
    const currentRequest = ++planRequestId.current
    setPlanState({ key: planKey, plan: null, loading: true, error: '' })
    loadWorkoutPlan(startDate, goal, equipment)
      .then((result) => {
        if (currentRequest === planRequestId.current) {
          setPlanState({ key: planKey, plan: result, loading: false, error: '' })
        }
      })
      .catch((err) => {
        if (currentRequest === planRequestId.current) {
          setPlanState({ key: planKey, plan: null, loading: false,
            error: err instanceof Error ? err.message : 'Failed to load workout plan' })
        }
      })
    return () => { planRequestId.current++ }
  }, [startDate, goal, equipment, planKey])

  const planDay = plan?.days.find((day) => day.date === dateLabel)

  return (
    <div className="workout-page">
      <section className="calendar-card workout-calendar">
        <h1>Workout calendar</h1>
        <p className="calendar-subtitle">Select a date to see your scheduled exercises.</p>
        <Calendar
          locale="en-US"
          value={selectedDate}
          onChange={(value) => {
            if (value instanceof Date) chooseDate(value)
          }}
        />
        <div className="workout-preferences">
          <h3>Weekly plan</h3>
          <label>
            Goal
            <select value={goal} onChange={(event) => setGoal(event.target.value)}>
              <option value="general_fitness">General fitness</option>
              <option value="strength">Strength</option>
              <option value="muscle_gain">Muscle gain</option>
            </select>
          </label>
          <label>
            Equipment
            <select value={equipment} onChange={(event) => setEquipment(event.target.value)}>
              <option value="bodyweight">Bodyweight</option>
              <option value="gym">Gym</option>
            </select>
          </label>
        </div>
      </section>

      <section className="card workout-details">
        <header className="workout-details__header">
          <div>
            <h2>Exercises for {dateLabel}</h2>
            <p className="calendar-subtitle">Edit an exercise below or add one for this date.</p>
          </div>
        </header>

        {loading && <p className="food-loading">Loading...</p>}
        {loadError && (
          <div role="alert">
            <p className="food-error">{loadError}</p>
            {notice && <p>Changes were saved, but the list could not be refreshed.</p>}
            <button type="button" onClick={() => void reload()} disabled={saving}>Retry loading exercises</button>
          </div>
        )}
        {error && <p className="food-error" role="alert">{error}</p>}
        {notice && <p className="userinfo-msg userinfo-msg--ok" role="status">{notice}</p>}

        {loaded && workouts.length === 0 && (
          <p className="food-empty">No saved exercises for this date.</p>
        )}
        {loaded && workouts.length > 0 && (
          <ul className="workout-list">
            {workouts.map((entry) => (
              <li className="workout-item" key={entry.id}>
                <div>
                  <strong>{entry.workout.workoutName}</strong>
                  <div className="workout-item__meta">
                    {entry.workout.rep} · Weight {entry.workout.weight}
                  </div>
                </div>
                <div className="workout-item__actions">
                  <button type="button" onClick={() => edit(entry)} disabled={saving}>Edit</button>
                  <button type="button" className="workout-item__delete"
                    onClick={() => requestDelete(entry)} disabled={saving}>Delete</button>
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="workout-suggestions">
          <h3>Suggested plan · {planDay?.focus ?? 'This week'}</h3>
          {planLoading && <p className="food-loading">Loading plan...</p>}
          {planError && <p className="food-error" role="alert">{planError}</p>}
          {!planLoading && planDay?.activity && (
            <p className="workout-suggestions__activity">
              {planDay.activity}{planDay.durationMinutes > 0 ? ` · ${planDay.durationMinutes} minutes` : ''}
            </p>
          )}
          {!planLoading && planDay && planDay.workouts.length > 0 && (
            <>
              <p className="calendar-subtitle">{plan?.weightGuidance}</p>
              <ul className="workout-list">
                {planDay.workouts.map((suggestion) => {
                  const alreadySaved = loaded && workouts.some((entry) =>
                    entry.workout.workoutName === suggestion.workoutName)
                  return (
                    <li className="workout-item" key={suggestion.workoutName}>
                      <div>
                        <strong>{suggestion.workoutName}</strong>
                        <div className="workout-item__meta">{suggestion.rep}</div>
                      </div>
                      <button type="button" className="workout-suggestions__add"
                        onClick={() => schedule(suggestion)} disabled={saving || !loaded || alreadySaved}>
                        {!loaded ? (loading ? 'Checking schedule...' : 'Schedule unavailable') : alreadySaved ? 'Scheduled' : 'Add to calendar'}
                      </button>
                    </li>
                  )
                })}
              </ul>
            </>
          )}
        </div>

        <form className="workout-form" onSubmit={(event) => { event.preventDefault(); void save() }}>
          <h3>{editingId === null ? 'Add exercise' : 'Edit exercise'}</h3>
          <p className="calendar-subtitle">Changing dates clears this form. Submitted changes continue for their original date.</p>
          <label>
            Exercise name
            <input value={draft.workoutName} onChange={(event) =>
              changeDraft('workoutName', event.target.value)} required />
          </label>
          <div className="workout-form__row">
            <label>
              Sets and reps
              <input value={draft.rep} placeholder="4X12" onChange={(event) =>
                changeDraft('rep', event.target.value)} required />
            </label>
            <label>
              Weight (0 for bodyweight or unset)
              <input type="number" min="0" step="any" value={draft.weight}
                onChange={(event) => changeDraft('weight', event.target.value)} required />
            </label>
          </div>
          <div className="workout-form__actions">
            {editingId !== null && (
              <button type="button" className="workout-form__cancel" onClick={resetForm} disabled={saving}>
                Cancel edit
              </button>
            )}
            <button type="submit" disabled={saving || !loaded}>{saving ? 'Saving...' : 'Save exercise'}</button>
          </div>
        </form>
      </section>

      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete exercise"
        message={`Delete ${pendingDelete?.workout.workoutName ?? 'this exercise'} from ${pendingDelete?.workout.workoutDate ?? dateLabel}?`}
        confirmText="Delete"
        danger
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
    </div>
  )
}
