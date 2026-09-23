import ApiKeyForm from './ApiKeyForm'
import UserInfoForm from './UserInfoForm'

interface SettingsPageProps {
  apiKeyConfigured: boolean
  onConfiguredChange: (configured: boolean) => void
}

// 设置页：收纳用户信息 + API Key 两个面板。
// 不承载业务逻辑，只负责把它们摆进设置页容器；各表单仍「谁的数据谁负责」。
export default function SettingsPage({ apiKeyConfigured, onConfiguredChange }: SettingsPageProps) {
  return (
    <div className="settings-page">
      <h1 className="settings-page__title">设置</h1>
      <UserInfoForm />
      <ApiKeyForm configured={apiKeyConfigured} onConfiguredChange={onConfiguredChange} />
    </div>
  )
}
