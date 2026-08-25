<template>
  <Transition name="dialog">
    <div v-if="request" class="fixed inset-0 z-[100] flex items-center justify-center p-4">
      <!-- No backdrop click-to-dismiss: the pairing decision must be explicit -->
      <div class="absolute inset-0 bg-background/60 backdrop-blur-md"></div>

      <div class="relative w-full max-w-md bg-surface-bright/95 backdrop-blur-2xl rounded-3xl overflow-hidden shadow-2xl border border-white/10 flex flex-col">
        <div class="absolute -top-32 -right-32 w-64 h-64 bg-primary/15 rounded-full blur-[80px] pointer-events-none"></div>
        <div class="absolute -bottom-32 -left-32 w-64 h-64 bg-error/10 rounded-full blur-[80px] pointer-events-none"></div>

        <div class="px-6 pt-6 pb-2 relative z-10 flex items-center gap-3">
          <div class="w-10 h-10 rounded-full bg-primary/15 text-primary flex items-center justify-center flex-shrink-0">
            <ShieldCheck class="w-5 h-5" />
          </div>
          <h3 class="text-lg font-extrabold text-on-surface tracking-wide">{{ $t('app.pairing.title') }}</h3>
        </div>

        <div class="px-6 py-3 relative z-10 flex flex-col items-center text-center gap-4">
          <p class="text-sm text-on-surface-variant leading-relaxed break-all">{{ $t('app.pairing.deviceWantsToPair', { name: request.deviceName }) }}</p>

          <p
            data-testid="pairing-sas"
            class="w-full font-mono font-bold text-5xl tracking-[0.3em] text-primary select-all px-2 py-4 rounded-2xl bg-surface-variant/30"
          >{{ request.sas }}</p>

          <p class="text-xs text-error leading-relaxed flex items-start justify-center gap-1.5">
            <ShieldAlert class="w-4 h-4 shrink-0 mt-0.5" />
            {{ $t('app.pairing.compareCode') }}
          </p>
        </div>

        <div class="px-6 py-4 flex items-center justify-end gap-2 relative z-10">
          <button
            @click="emit('deny')"
            class="px-4 py-2 rounded-xl text-sm font-medium text-on-surface-variant hover:bg-surface-variant/60 transition-colors"
          >
            {{ $t('app.pairing.deny') }}
          </button>
          <button
            @click="emit('approve')"
            class="px-4 py-2 rounded-xl bg-primary hover:bg-primary/90 text-on-primary text-sm font-semibold shadow-md transition-all hover:scale-[0.99] active:scale-95"
          >
            {{ $t('app.pairing.approve') }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ShieldCheck, ShieldAlert } from '@lucide/vue';
import type { PairingRequest } from '../composables/useServer';

defineProps<{
  request: PairingRequest | null;
}>();

const emit = defineEmits<{
  (e: 'approve'): void;
  (e: 'deny'): void;
}>();
</script>

<style scoped>
.dialog-enter-active,
.dialog-leave-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
.dialog-enter-from .relative,
.dialog-leave-to .relative {
  transform: scale(0.95) translateY(8px);
}
</style>
