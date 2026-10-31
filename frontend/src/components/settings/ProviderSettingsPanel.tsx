import ActiveProviderField from './ActiveProviderField'
import ModelPicker from './ModelPicker'
import ProviderKeyForm from './ProviderKeyForm'
import type { LlmSettingsController } from './useLlmSettings'

interface ProviderSettingsPanelProps {
  llm: LlmSettingsController
}

// Model provider settings: the provider used for chat, then each provider's key and model.
// Each form saves only its own change; App owns the shared state through useLlmSettings.
export default function ProviderSettingsPanel({ llm }: ProviderSettingsPanelProps) {
  const { state } = llm

  return (
    <section className="llm-card" aria-labelledby="llm-card-title">
      <h2 id="llm-card-title">Model providers</h2>
      <p className="llm-msg">
        Each provider needs its own API key, billed per use by that provider. Keys are saved on
        this computer and are never shown again.
      </p>

      {state.status === 'loading' && <p className="llm-msg" role="status">Loading provider settings…</p>}

      {state.status === 'error' && (
        <div className="llm-load-error" role="alert">
          <p className="llm-msg llm-msg--err">{state.message}</p>
          <button type="button" onClick={llm.reload}>Try again</button>
        </div>
      )}

      {state.status === 'ready' && (
        <>
          <ActiveProviderField settings={state.settings} onSave={llm.saveActiveProvider} />
          {state.settings.providers.map((provider) => (
            <section key={provider.id} className="llm-provider" aria-labelledby={`llm-${provider.id}`}>
              <h3 id={`llm-${provider.id}`}>
                {provider.name}
                {provider.id === state.settings.activeProvider && (
                  <span className="llm-tag llm-tag--active">Used for chat</span>
                )}
              </h3>
              <ProviderKeyForm provider={provider} onSave={(key) => llm.saveKey(provider.id, key)} />
              <ModelPicker provider={provider} onSave={(model) => llm.saveModel(provider.id, model)} />
            </section>
          ))}
        </>
      )}
    </section>
  )
}
