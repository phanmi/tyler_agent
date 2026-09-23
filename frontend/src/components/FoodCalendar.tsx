import { useCallback, useEffect, useMemo, useState } from 'react'
import Calendar from 'react-calendar'
import 'react-calendar/dist/Calendar.css'
import { deleteFood, deleteFoodByDate, loadFoodByDate } from '../api'
import type { FoodEntry } from '../types'
import ConfirmDialog from './ConfirmDialog'

// 把本地 Date 转成后端约定的 YYYY-MM-DD（本地时区，与后端日期语义一致）。
function toDateString(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

// 数值展示：null / undefined 归「未填」，否则原样输出（BigDecimal 经 JSON 已是 number）。
function fmt(value?: number | null): string {
  return value == null ? '—' : String(value)
}

// 待确认的删除操作：单条（携带完整 entry）或整日。
type PendingDelete = { kind: 'one'; entry: FoodEntry } | { kind: 'day' } | null

// 饮食日历面板：点选日期 → 拉取当天食物记录 → 逐条展示 + 当日营养汇总 + 删除入口。
// 状态只属于本组件（选中日期 / 食物列表 / loading / error），不干扰聊天流的消息状态。
export default function FoodCalendar() {
  const [selectedDate, setSelectedDate] = useState<Date>(() => new Date())
  const [foods, setFoods] = useState<FoodEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<PendingDelete>(null)

  // 点选日期后拉取当天食物记录。
  const load = useCallback(async (date: Date) => {
    setLoading(true)
    setError('')
    try {
      const result = await loadFoodByDate(toDateString(date))
      setFoods(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : '未知错误')
      setFoods([])
    } finally {
      setLoading(false)
    }
  }, [])

  // 日期变化时自动重新加载。
  useEffect(() => {
    load(selectedDate)
  }, [selectedDate, load])

  // 点「删除」只是打开确认框，真正删除在 ConfirmDialog 的 onConfirm 里执行。
  // 用自定义 modal 取代 window.confirm：Electron 无边框窗口下原生同步对话框关闭后
  // 焦点不归还渲染进程，会导致整个应用无法输入/点击，故这里改为完全非阻塞的自绘确认框。
  const requestDeleteOne = useCallback((entry: FoodEntry) => {
    setPending({ kind: 'one', entry })
  }, [])

  const requestDeleteDay = useCallback(() => {
    setPending({ kind: 'day' })
  }, [])

  const cancelDelete = useCallback(() => {
    setPending(null)
  }, [])

  // 确认删除：按 pending 类型执行对应删除，先关弹窗再落删，删除后重新拉取列表。
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
      setError(err instanceof Error ? err.message : '未知错误')
    }
  }, [pending, selectedDate, load])

  // 当日营养汇总：null 按 0 参与求和。
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
      <h2>饮食日历</h2>
      <p className="calendar-subtitle">点选日期，看看那天吃了什么。</p>

      <Calendar
        onChange={(value) => {
          if (value instanceof Date) {
            setSelectedDate(value)
          }
        }}
        value={selectedDate}
      />

      <div className="food-panel">
        <h3>{toDateString(selectedDate)} 的记录</h3>

        {loading && <div className="food-loading">加载中……</div>}

        {!loading && error && <div className="food-error">{error}</div>}

        {!loading && !error && foods.length === 0 && (
          <div className="food-empty">这一天还没有记录。</div>
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
                      <span className="food-item__name">{info?.foodName ?? '未命名'}</span>
                      <span className="food-item__amount">
                        {fmt(info?.amount)} {info?.unit ?? ''}
                      </span>
                      <span className="food-item__calories">{fmt(info?.calories)} kcal</span>
                      <button
                        className="food-item__delete"
                        type="button"
                        onClick={() => requestDeleteOne(entry)}
                      >
                        删除
                      </button>
                    </div>
                    <div className="food-item__macros">
                      <span>蛋白 {fmt(macro?.protein)}g</span>
                      <span>碳水 {fmt(macro?.carbs)}g</span>
                      <span>脂肪 {fmt(macro?.fat)}g</span>
                      <span>纤维 {fmt(macro?.fiber)}g</span>
                    </div>
                  </li>
                )
              })}
            </ul>

            <div className="food-summary">
              <strong>当日合计</strong>
              <span>热量 {totals.calories} kcal</span>
              <span>蛋白 {totals.protein}g</span>
              <span>碳水 {totals.carbs}g</span>
              <span>脂肪 {totals.fat}g</span>
              <span>纤维 {totals.fiber}g</span>
            </div>

            <button
              className="food-delete-day"
              type="button"
              onClick={requestDeleteDay}
            >
              删除这一天
            </button>
          </>
        )}
      </div>
      <ConfirmDialog
        open={pending !== null}
        title={pending?.kind === 'one' ? '删除这条记录' : '删除这一天'}
        message={
          pending?.kind === 'one'
            ? `确定删除「${pending.entry.food.genericInfo?.foodName ?? '未命名'}」这条记录吗？`
            : `确定删除 ${toDateString(selectedDate)} 这一天的全部记录吗？此操作不可撤销。`
        }
        confirmText="删除"
        danger
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
    </aside>
  )
}
