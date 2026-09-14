import { useCallback, useEffect, useMemo, useState } from 'react'
import Calendar from 'react-calendar'
import 'react-calendar/dist/Calendar.css'
import { loadFoodByDate } from '../api'
import type { Food } from '../types'

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

// 饮食日历面板：点选日期 → 拉取当天食物记录 → 逐条展示 + 当日营养汇总。
// 状态只属于本组件（选中日期 / 食物列表 / loading / error），不干扰聊天流的消息状态。
export default function FoodCalendar() {
  const [selectedDate, setSelectedDate] = useState<Date>(() => new Date())
  const [foods, setFoods] = useState<Food[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

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

  // 当日营养汇总：null 按 0 参与求和。
  const totals = useMemo(() => {
    const sum = (values: (number | null | undefined)[]) =>
      values.reduce<number>((acc, v) => acc + (v ?? 0), 0)
    return {
      calories: sum(foods.map((f) => f.genericInfo?.calories)),
      protein: sum(foods.map((f) => f.macroNutrients?.protein)),
      carbs: sum(foods.map((f) => f.macroNutrients?.carbs)),
      fat: sum(foods.map((f) => f.macroNutrients?.fat)),
      fiber: sum(foods.map((f) => f.macroNutrients?.fiber)),
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
              {foods.map((food, index) => {
                const info = food.genericInfo
                const macro = food.macroNutrients
                return (
                  <li className="food-item" key={`${toDateString(selectedDate)}-${index}`}>
                    <div className="food-item__head">
                      <span className="food-item__name">{info?.foodName ?? '未命名'}</span>
                      <span className="food-item__amount">
                        {fmt(info?.amount)} {info?.unit ?? ''}
                      </span>
                      <span className="food-item__calories">{fmt(info?.calories)} kcal</span>
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
          </>
        )}
      </div>
    </aside>
  )
}
