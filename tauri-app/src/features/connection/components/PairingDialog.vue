<script setup lang="ts">
import { ShieldCheck, ShieldAlert, Loader2 } from '@lucide/vue';
import type { PairingRequest } from '@/platform';

defineProps<{ request: PairingRequest | null; busy: boolean }>();
const emit = defineEmits<{ approve: []; deny: [] }>();
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="request" class="fixed inset-0 z-100 flex items-center justify-center bg-background/70 p-4">
        <section
          role="dialog"
          aria-modal="true"
          aria-labelledby="pairing-title"
          class="relative w-full max-w-md rounded-3xl border border-outline/15 bg-surface-bright p-6 shadow-2xl"
        >
          <h2 id="pairing-title" class="flex items-center gap-3 text-lg font-bold text-on-surface">
            <ShieldCheck class="h-6 w-6 text-primary" />
            {{ $t('app.pairing.title') }}
          </h2>
          <p class="mt-4 break-words text-sm leading-relaxed text-on-surface-variant">
            {{ $t('app.pairing.deviceWantsToPair', { name: request.deviceName }) }}
          </p>
          <p data-testid="pairing-sas" class="my-5 select-all rounded-2xl bg-primary/10 py-5 text-center font-mono text-4xl font-bold tracking-[0.25em] text-primary">
            {{ request.sas }}
          </p>
          <p class="flex items-start gap-2 text-sm leading-relaxed text-on-surface-variant">
            <ShieldAlert class="mt-0.5 h-4 w-4 shrink-0 text-error" />
            {{ $t('app.pairing.compareCode') }}
          </p>
          <div class="mt-6 flex justify-end gap-2">
            <button
              :disabled="busy"
              class="rounded-full px-4 py-2.5 text-sm font-medium text-on-surface-variant hover:bg-surface-variant/50 disabled:opacity-50"
              @click="emit('deny')"
            >{{ $t('app.pairing.deny') }}</button>
            <button
              :disabled="busy"
              class="flex items-center gap-2 rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-on-primary hover:opacity-90 disabled:opacity-50"
              @click="emit('approve')"
            >
              <Loader2 v-if="busy" class="h-4 w-4 animate-spin" />
              {{ $t('app.pairing.approve') }}
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
