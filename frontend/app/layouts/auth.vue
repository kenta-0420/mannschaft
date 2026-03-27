<template>
  <div class="flex min-h-screen items-center justify-center bg-surface-ground">
    <div class="w-full max-w-md px-4">
      <div class="mb-8 text-center">
        <h1 class="text-3xl font-bold text-primary">
          Mannschaft
        </h1>
      </div>
      <Card class="overflow-hidden">
        <template #content>
          <div class="p-2">
            <slot />
          </div>
        </template>
      </Card>
    </div>
  </div>
</template>
