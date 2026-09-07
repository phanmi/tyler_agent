import { useEffect, useState } from 'react'
import { loadUserInfo, saveUserInfo } from '../api'
import type { Gender, UserInfo } from '../types'

// 快捷别名：方便下面 Partial 更新时引用分组结构。
type UserPart = UserInfo['User']
type OtherPart = UserInfo['Other']

// 空结构工厂：所有字段留空，Age 为 null（与后端空结构、落盘 JSON 完全对齐）。
function emptyUserInfo(): UserInfo {
  return {
    User: { Name: '', Gender: '', Age: null, JobType: '' },
    Other: { Info: '', expectationFromLLM: '' },
  }
}

// 性别下拉选项：空串 = 未选择。
const GENDER_OPTIONS: { value: Gender; label: string }[] = [
  { value: '', label: '未选择' },
  { value: '男', label: '男' },
  { value: '女', label: '女' },
  { value: '其他', label: '其他' },
]

// 保存状态机：控制按钮文案与提示信息。
type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

// 用户信息面板：可编辑 7 个字段，点「保存」写回本地 JSON，启动时自动读取回填。
// 为什么数据放在组件内部而不是 App？
//   这块表单数据只被表单自己使用，和聊天消息没有任何交互，
//   所以「谁的数据，谁负责」——放在 UserInfoForm 内部，App 不背这个状态。
export default function UserInfoForm() {
  const [form, setForm] = useState<UserInfo>(emptyUserInfo)
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [errorMsg, setErrorMsg] = useState('')

  // 启动时自动读取一次已保存的信息并回填到表单。
  // 用 cancelled 标志避免组件在请求返回前被卸载时再去 setState（React 严格模式会双跑 effect）。
  useEffect(() => {
    let cancelled = false
    loadUserInfo()
      .then((data) => {
        if (!cancelled) setForm(data)
      })
      .catch(() => {
        // 后端未启动或读取失败时，保持空结构，不阻塞用户继续使用。
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 更新「基本信息」分组的某个字段（Partial 合并，其余字段不动）。
  const updateUserField = (patch: Partial<UserPart>) => {
    setForm((prev) => ({ ...prev, User: { ...prev.User, ...patch } }))
    setStatus('idle') // 一旦开始改，就清掉上次的保存结果提示。
  }

  // 更新「其他」分组的某个字段。
  const updateOtherField = (patch: Partial<OtherPart>) => {
    setForm((prev) => ({ ...prev, Other: { ...prev.Other, ...patch } }))
    setStatus('idle')
  }

  // 点「保存」：先本地校验 Age 必须为整数，再交给 api 写盘。
  const handleSave = async () => {
    const age = form.User.Age
    // Number.isInteger 同时排除了小数和非数字；null（未填写）直接放行。
    if (age !== null && !Number.isInteger(age)) {
      setStatus('error')
      setErrorMsg('年龄必须为整数（或留空）')
      return
    }

    setStatus('saving')
    setErrorMsg('')
    try {
      const saved = await saveUserInfo(form)
      setForm(saved) // 以后端返回的归一化结果为准回填。
      setStatus('saved')
    } catch (err) {
      setStatus('error')
      setErrorMsg(err instanceof Error ? err.message : '保存失败')
    }
  }

  return (
    <aside className="userinfo-card">
      <h2>用户信息</h2>
      <p className="userinfo-subtitle">
        保存后会以 JSON 形式写入本地文件，下次打开自动回填。
      </p>

      <fieldset>
        <legend>基本信息</legend>
        <label>
          姓名
          <input
            value={form.User.Name}
            onChange={(e) => updateUserField({ Name: e.target.value })}
            placeholder="可选"
          />
        </label>
        <label>
          性别
          <select
            value={form.User.Gender}
            onChange={(e) => updateUserField({ Gender: e.target.value as Gender })}
          >
            {GENDER_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          年龄
          <input
            type="number"
            step={1}
            value={form.User.Age ?? ''}
            onChange={(e) =>
              updateUserField({ Age: e.target.value === '' ? null : Number(e.target.value) })
            }
            placeholder="整数，可选"
          />
        </label>
        <label>
          职业类型
          <input
            value={form.User.JobType}
            onChange={(e) => updateUserField({ JobType: e.target.value })}
            placeholder="可选"
          />
        </label>
      </fieldset>

      <fieldset>
        <legend>其他</legend>
        <label>
          补充信息
          <textarea
            value={form.Other.Info}
            onChange={(e) => updateOtherField({ Info: e.target.value })}
            rows={2}
            placeholder="可选"
          />
        </label>
        <label>
          对 LLM 的期望
          <textarea
            value={form.Other.expectationFromLLM}
            onChange={(e) => updateOtherField({ expectationFromLLM: e.target.value })}
            rows={2}
            placeholder="可选"
          />
        </label>
      </fieldset>

      <button type="button" onClick={handleSave} disabled={status === 'saving'}>
        {status === 'saving' ? '保存中……' : '保存'}
      </button>

      {status === 'saved' && <p className="userinfo-msg userinfo-msg--ok">已保存 ✓</p>}
      {status === 'error' && <p className="userinfo-msg userinfo-msg--err">{errorMsg}</p>}
    </aside>
  )
}