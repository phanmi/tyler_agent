import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import * as api from '../../src/api'
import FoodCalendar from '../../src/components/food/FoodCalendar'
import type { FoodEntry } from '../../src/types'
import { deferred } from '../helpers/deferred'

vi.mock('../../src/api')
// Calendar's date selection is the only third-party UI boundary replaced in these workflow tests.
vi.mock('react-calendar', () => ({
  default: ({ onChange }: { onChange: (date: Date) => void }) => (
    <nav aria-label="Calendar dates">
      <button onClick={() => onChange(new Date(2026, 9, 16, 12))}>Select A</button>
      <button onClick={() => onChange(new Date(2026, 9, 17, 12))}>Select B</button>
    </nav>
  ),
}))

const A = '2026-10-16'
const B = '2026-10-17'

type Pending<T> = ReturnType<typeof deferred<T>>
let reads: { date: string; pending: Pending<FoodEntry[]> }[]
let deletes: { target: number | string; pending: Pending<boolean> }[]

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 16, 12))
  reads = []
  deletes = []
  vi.mocked(api.loadFoodByDate).mockImplementation((date) => {
    const pending = deferred<FoodEntry[]>()
    reads.push({ date, pending })
    return pending.promise
  })
  const remove = (target: number | string) => {
    const pending = deferred<boolean>()
    deletes.push({ target, pending })
    return pending.promise
  }
  vi.mocked(api.deleteFood).mockImplementation(remove)
  vi.mocked(api.deleteFoodByDate).mockImplementation(remove)
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

const food = (id: number, foodName: string, date = A): FoodEntry => ({
  id,
  food: {
    genericInfo: { foodName, amount: 1, unit: 'serving', calories: 100, date },
    macroNutrients: { protein: 1, carbs: 1, fat: 1, fiber: 1 },
  },
})

const select = (name: 'Select A' | 'Select B') => fireEvent.click(screen.getByRole('button', { name }))
const lastRead = (date: string) => {
  const matching = reads.filter((read) => read.date === date)
  return matching[matching.length - 1]
}
const settle = (read: { pending: Pending<FoodEntry[]> }, result: FoodEntry[] | Error) =>
  act(async () => (result instanceof Error ? read.pending.reject(result) : read.pending.resolve(result)))
const finishDelete = (index: number, result: true | Error) =>
  act(async () =>
    result instanceof Error ? deletes[index].pending.reject(result) : deletes[index].pending.resolve(result),
  )
const heading = () => screen.getByRole('heading', { level: 3 }).textContent
const recordButtons = () =>
  within(screen.getByRole('list')).getAllByRole<HTMLButtonElement>('button', { name: 'Delete' })
const dayButton = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Delete all for this day' })
const dialog = () => screen.queryByRole('dialog')
const confirm = () => fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))

async function showA(foods: FoodEntry[]) {
  render(<FoodCalendar />)
  await settle(reads[0], foods)
}

it.each(['succeeds', 'fails'] as const)(
  'ignores a previous date read that %s after the new date loaded',
  async (outcome) => {
    render(<FoodCalendar />)
    select('Select B')
    await settle(lastRead(B), [food(2, 'Rice', B)])
    await settle(lastRead(A), outcome === 'succeeds' ? [food(1, 'Oats')] : new Error('Old read failed'))

    expect(heading()).toBe(`Records for ${B}`)
    expect(screen.getByText('Rice')).toBeTruthy()
    expect(screen.queryByText('Oats')).toBeNull()
    expect(screen.queryByText('Old read failed')).toBeNull()
  },
)

it('keeps a repeated visit loading until its own read settles, ignoring older reads for the same date', async () => {
  render(<FoodCalendar />)
  select('Select B')
  select('Select A')
  const [firstA, readB, secondA] = reads
  expect(secondA.date).toBe(A)

  // Older completions must not end the current spinner or present their records.
  await settle(firstA, [food(1, 'Stale oats')])
  await settle(readB, new Error('B failed'))
  expect(screen.getByText('Loading...')).toBeTruthy()
  expect(screen.queryByText('Stale oats')).toBeNull()
  expect(screen.queryByText('B failed')).toBeNull()

  await settle(secondA, [food(2, 'Fresh oats')])
  expect(screen.queryByText('Loading...')).toBeNull()
  expect(screen.getByText('Fresh oats')).toBeTruthy()
})

it('shows a failed load as an error rather than an empty day', async () => {
  render(<FoodCalendar />)
  await settle(reads[0], new Error('Server unavailable'))

  expect(screen.getByText('Server unavailable')).toBeTruthy()
  expect(screen.queryByText('No records for this day.')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Delete all for this day' })).toBeNull()
})

it('deletes the captured record among equal-looking records, and cancel never writes', async () => {
  await showA([food(1, 'Apple'), food(2, 'Apple')])

  fireEvent.click(recordButtons()[1])
  expect(screen.getByText(`Delete the record for "Apple" from ${A}?`)).toBeTruthy()
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))
  fireEvent.click(recordButtons()[1])
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(dialog()).toBeNull()
  expect(deletes).toHaveLength(0)

  fireEvent.click(recordButtons()[1])
  confirm()
  expect(deletes.map((d) => d.target)).toEqual([2])
})

it('closes a day confirmation without writing when the date changes, and deletes only the confirmed date', async () => {
  await showA([food(1, 'Apple')])
  fireEvent.click(dayButton())
  expect(screen.getByText(`Delete all records for ${A}? This cannot be undone.`)).toBeTruthy()

  // The overlay blocks pointer input but not keyboard focus, so the calendar can still change the date.
  select('Select B')
  expect(dialog()).toBeNull()
  expect(deletes).toHaveLength(0)

  await settle(lastRead(B), [food(2, 'Rice', B)])
  fireEvent.click(dayButton())
  expect(screen.getByText(`Delete all records for ${B}? This cannot be undone.`)).toBeTruthy()
  confirm()
  expect(vi.mocked(api.deleteFoodByDate).mock.calls).toEqual([[B]])
})

it('allows one delete per date at a time, then refreshes the day after success', async () => {
  await showA([food(1, 'Apple'), food(2, 'Pear')])
  fireEvent.click(recordButtons()[0])
  confirm()

  expect(screen.getByRole('status').textContent).toBe('Deleting...')
  expect(recordButtons().every((button) => button.disabled)).toBe(true)
  expect(dayButton().disabled).toBe(true)
  fireEvent.click(recordButtons()[1])
  fireEvent.click(dayButton())
  expect(dialog()).toBeNull()
  expect(deletes).toHaveLength(1)

  await finishDelete(0, true)
  expect(screen.getByText('Loading...')).toBeTruthy()
  expect(screen.queryByText('Apple')).toBeNull()
  await settle(lastRead(A), [food(2, 'Pear')])
  expect(screen.queryByRole('status')).toBeNull()
  expect(recordButtons().map((button) => button.disabled)).toEqual([false])
})

it('keeps a failed delete on the current day visible without reloading it', async () => {
  await showA([food(1, 'Apple')])
  fireEvent.click(recordButtons()[0])
  confirm()
  await finishDelete(0, new Error('Delete failed'))

  expect(screen.getByRole('alert').textContent).toBe('Delete failed')
  expect(screen.getByText('Apple')).toBeTruthy()
  expect(recordButtons()[0].disabled).toBe(false)
  expect(reads).toHaveLength(1)
})

it.each([
  ['succeeds', true],
  ['fails', new Error('Delete failed')],
] as const)('leaves another date untouched when a delete %s after navigation', async (_, result) => {
  await showA([food(1, 'Apple')])
  fireEvent.click(recordButtons()[0])
  confirm()
  select('Select B')
  await settle(lastRead(B), [food(2, 'Rice', B)])
  // Date A's pending delete does not lock date B.
  expect(recordButtons()[0].disabled).toBe(false)

  await finishDelete(0, result)
  expect(heading()).toBe(`Records for ${B}`)
  expect(screen.getByText('Rice')).toBeTruthy()
  expect(screen.queryByRole('alert')).toBeNull()
  expect(recordButtons()[0].disabled).toBe(false)
  expect(reads.filter((read) => read.date === A)).toHaveLength(1)
})

it('does not let a read started before a delete finished restore the deleted record', async () => {
  await showA([food(1, 'Apple'), food(2, 'Pear')])
  fireEvent.click(recordButtons()[0])
  confirm()
  select('Select B')
  select('Select A')
  const staleRead = lastRead(A)

  await finishDelete(0, true)
  const refresh = lastRead(A)
  expect(refresh).not.toBe(staleRead)
  await settle(staleRead, [food(1, 'Apple'), food(2, 'Pear')])
  expect(screen.queryByText('Apple')).toBeNull()

  await settle(refresh, [food(2, 'Pear')])
  expect(screen.getByText('Pear')).toBeTruthy()
  expect(screen.queryByText('Apple')).toBeNull()
})
