import { useCallback, useEffect, useMemo, useState } from 'react'
import Calendar from 'react-calendar'
import 'react-calendar/dist/Calendar.css'
import { deleteFood, deleteFoodByDate, loadFoodByDate } from '../api'
import type { FoodEntry } from '../types'
import ConfirmDialog from './ConfirmDialog'

// Format a local Date as YYYY-MM-DD, matching the backend's date convention.
function toDateString(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

// Show an em dash for missing values; JSON already represents BigDecimal values as numbers.
function fmt(value?: number | null): string {
  return value == null ? '—' : String(value)
}

// Pending deletion: one complete entry or all records for a day.
type PendingDelete = { kind: 'one'; entry: FoodEntry } | { kind: 'day' } | null

// Select a date to load food records, nutrition totals, and deletion controls.
// Calendar state belongs here and does not affect chat message state.
export default function FoodCalendar() {
  const [selectedDate, setSelectedDate] = useState<Date>(() => new Date())
  const [foods, setFoods] = useState<FoodEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<PendingDelete>(null)

  // Load food records for the selected date.
  const load = useCallback(async (date: Date) => {
    setLoading(true)
    setError('')
    try {
      const result = await loadFoodByDate(toDateString(date))
      setFoods(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
      setFoods([])
    } finally {
      setLoading(false)
    }
  }, [])

  // Reload when the selected date changes.
  useEffect(() => {
    load(selectedDate)
  }, [selectedDate, load])

  // The delete button opens a dialog; ConfirmDialog.onConfirm performs the deletion.
  // A custom modal avoids focus issues caused by native synchronous dialogs
  // in frameless Electron windows, keeping the app responsive.
  const requestDeleteOne = useCallback((entry: FoodEntry) => {
    setPending({ kind: 'one', entry })
  }, [])

  const requestDeleteDay = useCallback(() => {
    setPending({ kind: 'day' })
  }, [])

  const cancelDelete = useCallback(() => {
    setPending(null)
  }, [])

  // Close the dialog, delete the selected entry or day, then reload the list.
  const confirmDelete = useCallback(async () => {
    if (!pending) return
    setPending(null)
    setError('')
    try {
      if (pending.kind === 'one') {
        await deleteFood(pending.entry.id)
      } else {
        await deleteFoodByDate(toDateString(selectedDate))
      }
      await load(selectedDate)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    }
  }, [pending, selectedDate, load])

  // Daily nutrition totals treat missing values as zero.
  const totals = useMemo(() => {
    const sum = (values: (number | null | undefined)[]) =>
      values.reduce<number>((acc, v) => acc + (v ?? 0), 0)
    return {
      calories: sum(foods.map((e) => e.food.genericInfo?.calories)),
      protein: sum(foods.map((e) => e.food.macroNutrients?.protein)),
      carbs: sum(foods.map((e) => e.food.macroNutrients?.carbs)),
      fat: sum(foods.map((e) => e.food.macroNutrients?.fat)),
      fiber: sum(foods.map((e) => e.food.macroNutrients?.fiber)),
    }
  }, [foods])

  return (
    <aside className="calendar-card">
      <h2>Food calendar</h2>
      <p className="calendar-subtitle">Choose a date to see what you ate.</p>

      <Calendar
        locale="en-US"
        onChange={(value) => {
          if (value instanceof Date) {
            setSelectedDate(value)
          }
        }}
        value={selectedDate}
      />

      <div className="food-panel">
        <h3>Records for {toDateString(selectedDate)}</h3>

        {loading && <div className="food-loading">Loading...</div>}

        {!loading && error && <div className="food-error">{error}</div>}

        {!loading && !error && foods.length === 0 && (
          <div className="food-empty">No records for this day.</div>
        )}

        {!loading && !error && foods.length > 0 && (
          <>
            <ul className="food-list">
              {foods.map((entry) => {
                const info = entry.food.genericInfo
                const macro = entry.food.macroNutrients
                return (
                  <li className="food-item" key={entry.id}>
                    <div className="food-item__head">
                      <span className="food-item__name">{info?.foodName ?? 'Unnamed'}</span>
                      <span className="food-item__amount">
                        {fmt(info?.amount)} {info?.unit ?? ''}
                      </span>
                      <span className="food-item__calories">{fmt(info?.calories)} kcal</span>
                      <button
                        className="food-item__delete"
                        type="button"
                        onClick={() => requestDeleteOne(entry)}
                      >
                        Delete
                      </button>
                    </div>
                    <div className="food-item__macros">
                      <span>Protein {fmt(macro?.protein)}g</span>
                      <span>Carbs {fmt(macro?.carbs)}g</span>
                      <span>Fat {fmt(macro?.fat)}g</span>
                      <span>Fiber {fmt(macro?.fiber)}g</span>
                    </div>
                  </li>
                )
              })}
            </ul>

            <div className="food-summary">
              <strong>Daily total</strong>
              <span>Calories {totals.calories} kcal</span>
              <span>Protein {totals.protein}g</span>
              <span>Carbs {totals.carbs}g</span>
              <span>Fat {totals.fat}g</span>
              <span>Fiber {totals.fiber}g</span>
            </div>

            <button
              className="food-delete-day"
              type="button"
              onClick={requestDeleteDay}
            >
              Delete all for this day
            </button>
          </>
        )}
      </div>
      <ConfirmDialog
        open={pending !== null}
        title={pending?.kind === 'one' ? 'Delete this record' : 'Delete all for this day'}
        message={
          pending?.kind === 'one'
            ? `Delete the record for "${pending.entry.food.genericInfo?.foodName ?? 'Unnamed'}"?`
            : `Delete all records for ${toDateString(selectedDate)}? This cannot be undone.`
        }
        confirmText="Delete"
        danger
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
    </aside>
  )
}
