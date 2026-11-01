import { useMemo } from 'react'
import Calendar from 'react-calendar'
import 'react-calendar/dist/Calendar.css'
import ConfirmDialog from '../common/ConfirmDialog'
import { useFoodDay } from './useFoodDay'

// Show an em dash for missing values; JSON already represents BigDecimal values as numbers.
function fmt(value?: number | null): string {
  return value == null ? '—' : String(value)
}

// Select a date to load food records, nutrition totals, and deletion controls.
// Calendar state belongs here and does not affect chat message state.
export default function FoodCalendar() {
  const day = useFoodDay()
  const { foods, pendingDelete } = day

  // Daily nutrition totals treat missing values as zero.
  const totals = useMemo(() => {
    const sum = (values: (number | null | undefined)[]) => values.reduce<number>((acc, v) => acc + (v ?? 0), 0)
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
            day.chooseDate(value)
          }
        }}
        value={day.selectedDate}
      />

      <div className="food-panel">
        <h3>Records for {day.dateLabel}</h3>

        {day.loading && <div className="food-loading">Loading...</div>}

        {!day.loading && day.loadError && <div className="food-error">{day.loadError}</div>}

        {!day.loading && !day.loadError && foods.length === 0 && (
          <div className="food-empty">No records for this day.</div>
        )}

        {day.deleteError && (
          <div className="food-error" role="alert">
            {day.deleteError}
          </div>
        )}

        {day.deleting && (
          <div className="food-loading" role="status">
            Deleting...
          </div>
        )}

        {foods.length > 0 && (
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
                        onClick={() => day.requestDeleteOne(entry)}
                        disabled={day.deleting}
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

            <button className="food-delete-day" type="button" onClick={day.requestDeleteDay} disabled={day.deleting}>
              Delete all for this day
            </button>
          </>
        )}
      </div>
      <ConfirmDialog
        open={pendingDelete !== null}
        title={pendingDelete?.kind === 'one' ? 'Delete this record' : 'Delete all for this day'}
        message={
          pendingDelete?.kind === 'one'
            ? `Delete the record for "${pendingDelete.entry.food.genericInfo?.foodName ?? 'Unnamed'}" from ${pendingDelete.date}?`
            : `Delete all records for ${pendingDelete?.date ?? day.dateLabel}? This cannot be undone.`
        }
        confirmText="Delete"
        danger
        onConfirm={() => void day.confirmDelete()}
        onCancel={day.cancelDelete}
      />
    </aside>
  )
}
