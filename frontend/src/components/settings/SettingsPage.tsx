import ProviderSettingsPanel from './ProviderSettingsPanel'
import UserInfoForm from './UserInfoForm'
import type { LlmSettingsController } from './useLlmSettings'

interface SettingsPageProps {
  // Provider settings are owned by App, which also uses them to gate chat.
  llm: LlmSettingsController
}

// Settings page containing the profile and model provider settings.
// This component handles layout; each form owns its own input and saving.
export default function SettingsPage({ llm }: SettingsPageProps) {
  return (
    <div className="settings-page">
      <h1 className="settings-page__title">Settings</h1>
      <UserInfoForm />
      <ProviderSettingsPanel llm={llm} />
    </div>
  )
}
