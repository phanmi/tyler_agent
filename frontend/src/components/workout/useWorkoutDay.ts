import { useCallback, useEffect, useRef, useState } from 'react'
import { createWorkout, deleteWorkout, loadWorkoutsByDate, updateWorkout } from '../../api'
import type { Workout, WorkoutEntry } from '../../types'

export function toDateString(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

interface WorkoutDraft {
  workoutName: string
  rep: string
  weight: string
}

interface Editor {
  date: string
  id: number | null
  fields: WorkoutDraft
  revision: number
  session: number
}

interface DayRead {
  date: string
  status: 'loading' | 'ready' | 'error'
  entries: WorkoutEntry[]
  error: string
}

const emptyFields = (): WorkoutDraft => ({ workoutName: '', rep: '', weight: '0' })
const message = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback

/** Owns a selected-day workflow. Refs guard synchronous re-entry; state renders its snapshots. */
export function useWorkoutDay() {
  const [selectedDate, setSelectedDate] = useState(() => new Date())
  const date = toDateString(selectedDate)
  // A visit identity also distinguishes A -> B -> A, so old feedback cannot affect a new visit.
  const scope = useRef({ date })
  const mounted = useRef(false)
  const requestId = useRef(0)
  const operations = useRef(new Map<string, symbol>())
  const [pendingDates, setPendingDates] = useState<ReadonlySet<string>>(new Set())
  const [read, setRead] = useState<DayRead>({ date, status: 'loading', entries: [], error: '' })
  const readRef = useRef(read)
  const [editor, setEditorState] = useState<Editor>({ date, id: null, fields: emptyFields(), revision: 0, session: 0 })
  const editorRef = useRef(editor)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pendingDelete, setPendingDeleteState] = useState<WorkoutEntry | null>(null)
  const deleteRef = useRef<WorkoutEntry | null>(null)

  const setEditor = (next: Editor) => {
    editorRef.current = next
    setEditorState(next)
  }
  const setPendingDelete = (entry: WorkoutEntry | null) => {
    deleteRef.current = entry
    setPendingDeleteState(entry)
  }
  const publishRead = useCallback((next: DayRead) => {
    readRef.current = next
    setRead(next)
  }, [])
  const isCurrent = (visit: { date: string }) => mounted.current && scope.current === visit

  const load = useCallback(async (visit: { date: string }) => {
    // An old mutation must not even start a read that supersedes the selected date's request.
    if (!mounted.current || scope.current !== visit) return
    const id = ++requestId.current
    const relevant = () => mounted.current && scope.current === visit && requestId.current === id
    publishRead({ date: visit.date, status: 'loading', entries: [], error: '' })
    try {
      const entries = await loadWorkoutsByDate(visit.date)
      if (!relevant()) return
      if (entries.some((entry) => entry.workout.workoutDate !== visit.date)) {
        throw new Error('Received exercises for a different date. Please reload.')
      }
      publishRead({ date: visit.date, status: 'ready', entries, error: '' })
    } catch (err) {
      if (relevant()) {
        publishRead({ date: visit.date, status: 'error', entries: [], error: message(err, 'Failed to load workouts') })
      }
    }
  }, [publishRead])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      requestId.current++
    }
  }, [])

  useEffect(() => {
    void load(scope.current)
    return () => { requestId.current++ }
  }, [date, load])

  const resetForm = () => {
    const current = editorRef.current
    setEditor({ date: scope.current.date, id: null, fields: emptyFields(),
      revision: current.revision + 1, session: current.session + 1 })
  }

  const chooseDate = (next: Date) => {
    const nextDate = toDateString(next)
    if (scope.current.date === nextDate) return
    scope.current = { date: nextDate }
    requestId.current++
    setSelectedDate(next)
    publishRead({ date: nextDate, status: 'loading', entries: [], error: '' })
    resetForm()
    setPendingDelete(null)
    setError('')
    setNotice('')
  }

  const changeDraft = (field: keyof WorkoutDraft, value: string) => {
    const current = editorRef.current
    setEditor({ ...current, fields: { ...current.fields, [field]: value }, revision: current.revision + 1 })
  }

  const ready = () => readRef.current.date === scope.current.date && readRef.current.status === 'ready'
  const availableEntry = (entry: WorkoutEntry) => ready() && entry.workout.workoutDate === scope.current.date &&
    readRef.current.entries.some((saved) => saved.id === entry.id)

  const edit = (entry: WorkoutEntry) => {
    if (operations.current.has(scope.current.date) || !availableEntry(entry)) return
    const current = editorRef.current
    setEditor({ date: entry.workout.workoutDate, id: entry.id,
      fields: { workoutName: entry.workout.workoutName, rep: entry.workout.rep, weight: String(entry.workout.weight) },
      revision: current.revision + 1, session: current.session + 1 })
    setError('')
    setNotice('')
  }

  const requestDelete = (entry: WorkoutEntry) => {
    if (operations.current.has(scope.current.date) || !availableEntry(entry)) return
    setPendingDelete(entry)
  }

  // Locks include the refresh, and are per date so an older operation cannot clear a newer day's pending state.
  const begin = () => {
    const visit = scope.current
    if (!mounted.current || !ready() || operations.current.has(visit.date)) return null
    const token = Symbol()
    operations.current.set(visit.date, token)
    setPendingDates(new Set(operations.current.keys()))
    requestId.current++
    setError('')
    setNotice('')
    return { visit, token, draft: editorRef.current }
  }

  const finish = (operation: NonNullable<ReturnType<typeof begin>>) => {
    if (operations.current.get(operation.visit.date) !== operation.token) return
    operations.current.delete(operation.visit.date)
    if (mounted.current) setPendingDates(new Set(operations.current.keys()))
  }

  const refreshAfterWrite = async (operation: NonNullable<ReturnType<typeof begin>>) => {
    // There is no cached day list: another date will read fresh on its next visit.
    // If the user returned to this date during the write, refresh that visit without old feedback/draft changes.
    if (mounted.current && scope.current.date === operation.visit.date) await load(scope.current)
  }

  const save = async () => {
    if (!ready() || operations.current.has(scope.current.date) || !mounted.current) return
    const submitted = editorRef.current
    if (submitted.date !== scope.current.date) return
    const workoutName = submitted.fields.workoutName.trim()
    const rep = submitted.fields.rep.trim()
    const weight = Number(submitted.fields.weight)
    if (!workoutName || !/^[1-9]\d*X[1-9]\d*$/.test(rep) || submitted.fields.weight.trim() === '' ||
        !Number.isFinite(weight) || weight < 0) {
      setError('Enter an exercise name, sets and reps like 4X12, and a nonnegative weight.')
      return
    }
    const operation = begin()
    if (!operation) return
    const payload: Workout = { workoutName, rep, weight, workoutDate: submitted.date }
    try {
      const saved = submitted.id === null ? await createWorkout(payload) : await updateWorkout(submitted.id, payload)
      if (isCurrent(operation.visit)) {
        const current = editorRef.current
        if (current.session === submitted.session) {
          if (current.revision === submitted.revision) resetForm()
          // Newer typing in the same new-record draft now edits the created ID instead of inserting it twice.
          else if (submitted.id === null) setEditor({ ...current, id: saved.id })
        }
        setNotice('Workout saved.')
      }
      await refreshAfterWrite(operation)
    } catch (err) {
      if (isCurrent(operation.visit)) setError(message(err, 'Failed to save workout'))
    } finally {
      finish(operation)
    }
  }

  const confirmDelete = async () => {
    const entry = deleteRef.current
    if (!entry || !availableEntry(entry)) return
    const operation = begin()
    if (!operation) return
    setPendingDelete(null)
    try {
      await deleteWorkout(entry.id)
      if (isCurrent(operation.visit)) {
        const current = editorRef.current
        if (current.id === entry.id && current.session === operation.draft.session) {
          if (current.revision === operation.draft.revision) resetForm()
          else setEditor({ ...current, id: null })
        }
        setNotice('Workout deleted.')
      }
      await refreshAfterWrite(operation)
    } catch (err) {
      if (isCurrent(operation.visit)) setError(message(err, 'Failed to delete workout'))
    } finally {
      finish(operation)
    }
  }

  const schedule = async (suggestion: Omit<Workout, 'weight'>) => {
    if (!ready() || suggestion.workoutDate !== scope.current.date ||
        readRef.current.entries.some((entry) => entry.workout.workoutName === suggestion.workoutName)) return
    const operation = begin()
    if (!operation) return
    const payload: Workout = { ...suggestion, weight: 0 }
    try {
      await createWorkout(payload)
      if (isCurrent(operation.visit)) setNotice('Exercise added to your calendar. Edit it to set your weight.')
      await refreshAfterWrite(operation)
    } catch (err) {
      if (isCurrent(operation.visit)) setError(message(err, 'Failed to schedule exercise'))
    } finally {
      finish(operation)
    }
  }

  const loaded = read.date === date && read.status === 'ready'
  return { selectedDate, dateLabel: date, chooseDate, workouts: loaded ? read.entries : [], loaded,
    loading: read.date !== date || read.status === 'loading', loadError: read.date === date ? read.error : '',
    saving: pendingDates.has(date), error, notice, draft: editor.fields, editingId: editor.id,
    changeDraft, resetForm, edit, save, requestDelete, pendingDelete, confirmDelete,
    cancelDelete: () => setPendingDelete(null), schedule, reload: () => load(scope.current) }
}
