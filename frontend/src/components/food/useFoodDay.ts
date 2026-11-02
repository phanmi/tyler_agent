import { useEffect, useRef, useState } from 'react'
import { deleteFood, deleteFoodByDate, loadFoodByDate } from '../../api'
import type { FoodEntry } from '../../types'

// Format a local Date as YYYY-MM-DD, matching the backend's date convention.
function toDateString(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

// One calendar selection. Each selection is a new object, so A -> B -> A visits stay distinct.
interface Visit {
  value: Date
  date: string
}

// A settled read belongs to one visit and data revision; for any other key the day is still loading.
interface FoodRead {
  visit: Visit
  revision: number
  foods: FoodEntry[]
  error: string
}

// The confirmed deletion target, captured when the confirmation opens.
type DeleteTarget = { kind: 'one'; date: string; entry: FoodEntry } | { kind: 'day'; date: string }

const visitOf = (value: Date): Visit => ({ value, date: toDateString(value) })
const message = (error: unknown, fallback: string) => (error instanceof Error ? error.message : fallback)

/** Owns the selected food day: its keyed read, delete confirmation, and per-date delete locks. */
export function useFoodDay() {
  const [visit, setVisit] = useState(() => visitOf(new Date()))
  // Async completions compare against the latest visit, which their closures cannot see.
  const visitRef = useRef(visit)
  // Bumped after a successful delete to re-read the affected day and discard reads started before it.
  const [revision, setRevision] = useState(0)
  const [read, setRead] = useState<FoodRead | null>(null)
  const [pendingDelete, setPendingDelete] = useState<{ visit: Visit; target: DeleteTarget } | null>(null)
  const [deleteError, setDeleteError] = useState<{ visit: Visit; message: string } | null>(null)
  // The ref blocks synchronous re-entry; the state copy renders which dates are busy.
  const locks = useRef(new Set<string>())
  const [lockedDates, setLockedDates] = useState<ReadonlySet<string>>(() => new Set())

  useEffect(() => {
    // Cleanup runs on every selection, refresh, and unmount, so only the latest read for the shown day can publish.
    let active = true
    const publish = (foods: FoodEntry[], error: string) => {
      if (active) setRead({ visit, revision, foods, error })
    }
    loadFoodByDate(visit.date).then(
      (foods) => publish(foods, ''),
      (err: unknown) => publish([], message(err, 'Unknown error')),
    )
    return () => {
      active = false
    }
  }, [visit, revision])

  const settled = read !== null && read.visit === visit && read.revision === revision ? read : null
  const ready = settled !== null && !settled.error
  const foods = ready ? settled.foods : []
  const deleting = lockedDates.has(visit.date)

  // Every selection, including the current date again, starts a new visit and reloads it.
  const chooseDate = (value: Date) => {
    const next = visitOf(value)
    visitRef.current = next
    setVisit(next)
    setPendingDelete(null)
  }

  // An open confirmation keeps the target captured when it opened; requests from behind the dialog are ignored.
  const canRequest = () => ready && pendingDelete === null && !locks.current.has(visit.date)

  const requestDeleteOne = (entry: FoodEntry) => {
    if (!canRequest() || !foods.some((food) => food.id === entry.id)) return
    setPendingDelete({ visit, target: { kind: 'one', date: visit.date, entry } })
  }

  const requestDeleteDay = () => {
    if (!canRequest()) return
    setPendingDelete({ visit, target: { kind: 'day', date: visit.date } })
  }

  const publishLocks = () => setLockedDates(new Set(locks.current))

  const confirmDelete = async () => {
    const pending = pendingDelete
    setPendingDelete(null)
    if (!pending || pending.visit !== visitRef.current || !ready || locks.current.has(pending.target.date)) return
    const { target } = pending
    locks.current.add(target.date)
    publishLocks()
    setDeleteError(null)
    try {
      if (target.kind === 'one') await deleteFood(target.entry.id)
      else await deleteFoodByDate(target.date)
      // Refresh the day only while it is shown; other days read fresh on their next visit.
      if (visitRef.current.date === target.date) setRevision((current) => current + 1)
    } catch (err) {
      // Like the refresh above, report to whichever visit of this date is shown, even one started while deleting.
      const current = visitRef.current
      if (current.date === target.date) setDeleteError({ visit: current, message: message(err, 'Failed to delete') })
    } finally {
      locks.current.delete(target.date)
      publishLocks()
    }
  }

  return {
    selectedDate: visit.value,
    dateLabel: visit.date,
    chooseDate,
    foods,
    loading: settled === null,
    loadError: settled?.error ?? '',
    deleting,
    deleteError: deleteError?.visit === visit ? deleteError.message : '',
    pendingDelete: pendingDelete?.target ?? null,
    requestDeleteOne,
    requestDeleteDay,
    cancelDelete: () => setPendingDelete(null),
    confirmDelete,
  }
}
