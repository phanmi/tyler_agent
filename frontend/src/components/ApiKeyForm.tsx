import { useState } from 'react'
import { saveApiKey } from '../api'

// 保存状态机：控制按钮文案与提示信息。
type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

interface ApiKeyFormProps {
  // 当前 key 是否已配置（由 App 统一加载并回填）。
  configured: boolean
  // 配置状态变化时回传给 App，让 App 决定聊天是否放行。
  onConfiguredChange: (configured: boolean) => void
}

// OpenAI API Key 面板：一个 password 输入框 + 保存按钮。
// 只负责「输入 + 保存」；是否配置的状态由父组件 App 下发，本组件不自行请求加载。
export default function ApiKeyForm({ configured, onConfiguredChange }: ApiKeyFormProps) {
  const [value, setValue] = useState('')
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [errorMsg, setErrorMsg] = useState('')

  // 点「保存」：本地校验非空后交给 api 写盘，成功后清空输入框、不回显 key。
  const handleSave = async () => {
    const apiKey = value.trim()
    if (!apiKey) {
      setStatus('error')
      setErrorMsg('请输入 API Key')
      return
    }

    setStatus('saving')
    setErrorMsg('')
    try {
      const res = await saveApiKey(apiKey)
      onConfiguredChange(res.configured)
      setValue('') // 保存成功后清空输入框，绝不在界面上回显 key。
      setStatus('saved')
    } catch (err) {
      setStatus('error')
      setErrorMsg(err instanceof Error ? err.message : '保存失败')
    }
  }

  return (
    <aside className="userinfo-card">
      <h2>OpenAI API Key</h2>
      <p className="userinfo-subtitle">
        密钥只保存在本地沙盒文件，保存后不回显。
      </p>

      <label>
        API Key
        <input
          type="password"
          value={value}
          onChange={(e) => {
            setValue(e.target.value)
            setStatus('idle')
          }}
          placeholder="sk-..."
          autoComplete="off"
        />
      </label>

      <button type="button" onClick={handleSave} disabled={status === 'saving'}>
        {status === 'saving' ? '保存中……' : '保存'}
      </button>

      <p className="userinfo-subtitle">
        状态：{configured ? '已设置 ✓' : '未设置'}
      </p>

      {status === 'saved' && <p className="userinfo-msg userinfo-msg--ok">已保存 ✓</p>}
      {status === 'error' && <p className="userinfo-msg userinfo-msg--err">{errorMsg}</p>}
    </aside>
  )
}
