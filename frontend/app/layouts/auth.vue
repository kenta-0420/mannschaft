<template>
  <div class="flex min-h-screen items-center justify-center" style="background-color: var(--bg-color, #f3efe0)">
    <div class="w-full max-w-md px-4">
      <div class="mb-8 text-center">
        <h1
          class="text-5xl font-bold text-primary"
          style="text-shadow: 0 1px 4px rgba(0, 0, 0, 0.07)"
        >
          Mannschaft
        </h1>
      </div>
      <Card
        class="overflow-hidden"
        style="background-color: #ffffff; box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1)"
      >
        <template #content>
          <div class="p-2">
            <slot />
          </div>
        </template>
      </Card>
    </div>
  </div>
</template>
