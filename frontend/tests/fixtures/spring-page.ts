import type { SpringPage } from '~/types/api'

/** HTTP境界で返るSpring Data Page。data/metaへ事前変換しない。 */
export function springPage<T>(content: T[], totalElements = content.length, number = 0, size = 20): SpringPage<T> {
  const sort = { empty: true, sorted: false, unsorted: true }
  return {
    content, totalElements, totalPages: Math.ceil(totalElements / size), number, size,
    empty: content.length === 0, first: number === 0, last: (number + 1) * size >= totalElements,
    numberOfElements: content.length, sort,
    pageable: { offset: number * size, pageNumber: number, pageSize: size, paged: true, unpaged: false, sort },
  }
}
