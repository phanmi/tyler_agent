import ApiKeyForm from './ApiKeyForm'
import UserInfoForm from './UserInfoForm'

interface SettingsPageProps {
  apiKeyConfigured: boolean
  onConfiguredChange: (configured: boolean) => void
}

// Settings page containing the profile and API key forms.
// This component handles layout; each form owns its data and behavior.
export default function SettingsPage({ apiKeyConfigured, onConfiguredChange }: SettingsPageProps) {
  return (
    <div className="settings-page">
      <h1 className="settings-page__title">Settings</h1>
      <UserInfoForm />
      <ApiKeyForm configured={apiKeyConfigured} onConfiguredChange={onConfiguredChange} />
    </div>
  )
}
