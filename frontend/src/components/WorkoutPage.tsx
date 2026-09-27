import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import Calendar from 'react-calendar'
import 'react-calendar/dist/Calendar.css'
import { createWorkout, deleteWorkout, loadWorkoutPlan, loadWorkoutsByDate, updateWorkout } from '../api'
import type { Workout, WorkoutEntry, WorkoutPlan } from '../types'
import ConfirmDialog from './ConfirmDialog'

function toDateString(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function weekStart(date: Date): string {
  const monday = new Date(date)
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))
  return toDateString(monday)
}

interface WorkoutDraft {
  workoutName: string
  rep: string
  weight: string
}

const EMPTY_DRAFT: WorkoutDraft = { workoutName: '', rep: '', weight: '0' }

export default function WorkoutPage() {
  const [selectedDate, setSelectedDate] = useState<Date>(() => new Date())
  const [workouts, setWorkouts] = useState<WorkoutEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState<WorkoutDraft>(EMPTY_DRAFT)
  const [pendingDelete, setPendingDelete] = useState<WorkoutEntry | null>(null)
  const [plan, setPlan] = useState<WorkoutPlan | null>(null)
  const [planLoading, setPlanLoading] = useState(false)
  const [planError, setPlanError] = useState('')
  const [goal, setGoal] = useState('general_fitness')
  const [equipment, setEquipment] = useState('bodyweight')
  const requestId = useRef(0)
  const planRequestId = useRef(0)

  const load = useCallback(async (date: Date) => {
    const currentRequest = ++requestId.current
    setLoading(true)
    setError('')
    try {
      const entries = await loadWorkoutsByDate(toDateString(date))
      if (currentRequest === requestId.current) setWorkouts(entries)
    } catch (err) {
      if (currentRequest === requestId.current) {
        setWorkouts([])
        setError(err instanceof Error ? err.message : 'Failed to load workouts')
      }
    } finally {
      if (currentRequest === requestId.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    load(selectedDate)
    return () => { requestId.current++ }
  }, [selectedDate, load])

  const startDate = weekStart(selectedDate)
  useEffect(() => {
    const currentRequest = ++planRequestId.current
    setPlan(null)
    setPlanLoading(true)
    setPlanError('')
    loadWorkoutPlan(startDate, goal, equipment)
      .then((result) => {
        if (currentRequest === planRequestId.current) setPlan(result)
      })
      .catch((err) => {
        if (currentRequest === planRequestId.current) {
          setPlanError(err instanceof Error ? err.message : 'Failed to load workout plan')
        }
      })
      .finally(() => {
        if (currentRequest === planRequestId.current) setPlanLoading(false)
      })
    return () => { planRequestId.current++ }
  }, [startDate, goal, equipment])

  const chooseDate = (date: Date) => {
    setSelectedDate(date)
    setEditingId(null)
    setDraft(EMPTY_DRAFT)
    setNotice('')
  }

  const edit = (entry: WorkoutEntry) => {
    setEditingId(entry.id)
    setDraft({
      workoutName: entry.workout.workoutName,
      rep: entry.workout.rep,
      weight: String(entry.workout.weight),
    })
    setError('')
    setNotice('')
  }

  const resetForm = () => {
    setEditingId(null)
    setDraft(EMPTY_DRAFT)
  }

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const workoutName = draft.workoutName.trim()
    const weight = Number(draft.weight)
    if (!workoutName || !/^[1-9]\d*X[1-9]\d*$/.test(draft.rep.trim()) ||
        draft.weight.trim() === '' || !Number.isFinite(weight) || weight < 0) {
      setError('Enter an exercise name, sets and reps like 4X12, and a nonnegative weight.')
      return
    }

    const workout: Workout = {
      workoutName,
      rep: draft.rep.trim(),
      weight,
      workoutDate: toDateString(selectedDate),
    }
    setSaving(true)
    setError('')
    setNotice('')
    try {
      if (editingId === null) {
        await createWorkout(workout)
      } else {
        await updateWorkout(editingId, workout)
      }
      resetForm()
      await load(selectedDate)
      setNotice('Workout saved.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save workout')
    } finally {
      setSaving(false)
    }
  }

  const confirmDelete = async () => {
    if (!pendingDelete) return
    const entry = pendingDelete
    setPendingDelete(null)
    setSaving(true)
    setError('')
    setNotice('')
    try {
      await deleteWorkout(entry.id)
      if (editingId === entry.id) resetForm()
      await load(selectedDate)
      setNotice('Workout deleted.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete workout')
    } finally {
      setSaving(false)
    }
  }

  const schedule = async (suggestion: WorkoutPlan['days'][number]['workouts'][number]) => {
    setSaving(true)
    setError('')
    setNotice('')
    try {
      await createWorkout({ ...suggestion, weight: 0 })
      await load(selectedDate)
      setNotice('Exercise added to your calendar. Edit it to set your weight.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to schedule exercise')
    } finally {
      setSaving(false)
    }
  }

  const dateLabel = toDateString(selectedDate)
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
        {error && <p className="food-error" role="alert">{error}</p>}
        {notice && <p className="userinfo-msg userinfo-msg--ok" role="status">{notice}</p>}

        {!loading && workouts.length === 0 && (
          <p className="food-empty">No saved exercises for this date.</p>
        )}
        {!loading && workouts.length > 0 && (
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
                    onClick={() => setPendingDelete(entry)} disabled={saving}>Delete</button>
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
                  const alreadySaved = workouts.some((entry) =>
                    entry.workout.workoutName === suggestion.workoutName)
                  return (
                    <li className="workout-item" key={suggestion.workoutName}>
                      <div>
                        <strong>{suggestion.workoutName}</strong>
                        <div className="workout-item__meta">{suggestion.rep}</div>
                      </div>
                      <button type="button" className="workout-suggestions__add"
                        onClick={() => schedule(suggestion)} disabled={saving || alreadySaved}>
                        {alreadySaved ? 'Scheduled' : 'Add to calendar'}
                      </button>
                    </li>
                  )
                })}
              </ul>
            </>
          )}
        </div>

        <form className="workout-form" onSubmit={save}>
          <h3>{editingId === null ? 'Add exercise' : 'Edit exercise'}</h3>
          <label>
            Exercise name
            <input value={draft.workoutName} onChange={(event) =>
              setDraft({ ...draft, workoutName: event.target.value })} required />
          </label>
          <div className="workout-form__row">
            <label>
              Sets and reps
              <input value={draft.rep} placeholder="4X12" onChange={(event) =>
                setDraft({ ...draft, rep: event.target.value })} required />
            </label>
            <label>
              Weight (0 for bodyweight or unset)
              <input type="number" min="0" step="any" value={draft.weight}
                onChange={(event) => setDraft({ ...draft, weight: event.target.value })} required />
            </label>
          </div>
          <div className="workout-form__actions">
            {editingId !== null && (
              <button type="button" className="workout-form__cancel" onClick={resetForm} disabled={saving}>
                Cancel edit
              </button>
            )}
            <button type="submit" disabled={saving}>{saving ? 'Saving...' : 'Save exercise'}</button>
          </div>
        </form>
      </section>

      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete exercise"
        message={`Delete ${pendingDelete?.workout.workoutName ?? 'this exercise'} from ${dateLabel}?`}
        confirmText="Delete"
        danger
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  )
}
