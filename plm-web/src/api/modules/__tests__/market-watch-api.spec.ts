import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  patch: vi.fn(),
  post: vi.fn()
}))

vi.mock('../../request', () => ({
  default: requestMock,
  unwrapResponse: <T>(response: { data: { data: T } }) => response.data.data,
  unwrapPage: <T>(response: { data: { data: T } }) => response.data.data
}))

import {
  getMarketWatchItems,
  getMarketWatchSummary,
  getMarketWatchModel,
  refreshMarketWatchProjection,
  linkMarketWatchPhoneModel
} from '../marketWatch'

function apiResponse<T>(data: T) {
  return Promise.resolve({ data: { code: 0, message: 'success', data } })
}

describe('market watch API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('passes paging and filters to the native PLM proxy', async () => {
    const query = { coverageState:'applying' as const, page: 3, size: 20, keyword: 'pixel', country: 'MX' }
    const page = { content: [], page: 3, size: 20, totalElements: 0, totalPages: 0 }
    requestMock.get.mockReturnValue(apiResponse(page))

    await expect(getMarketWatchItems(query)).resolves.toEqual(page)
    expect(requestMock.get).toHaveBeenCalledWith('/market-watch/items', { params: query })
  })

  it('preserves aggregate statistics and encodes canonical phone keys while matching by real ID', async () => {
    const aggregate = { content:[], stats:{total:2,matched:1,unmatched:1,ambiguous:0}, sync:{lastError:'保留缓存'} }
    requestMock.get.mockReturnValue(apiResponse(aggregate))
    await expect(getMarketWatchItems({page:1,size:50,coverageState:'making'})).resolves.toEqual(aggregate)
    await getMarketWatchSummary({page:1,size:50,coverageState:'making'})
    expect(requestMock.get).toHaveBeenLastCalledWith('/market-watch/summary',{params:{page:1,size:50,coverageState:'making'}})
    await getMarketWatchModel('phone:17')
    expect(requestMock.get).toHaveBeenLastCalledWith('/market-watch/items/phone%3A17')
    requestMock.patch.mockReturnValue(apiResponse({id:9,phoneModelId:17}))
    await linkMarketWatchPhoneModel('phone',9,{phoneModelId:17,originalPhoneModelId:null})
    expect(requestMock.patch).toHaveBeenLastCalledWith('/market-watch/items/phone/9/phone-model',{phoneModelId:17,originalPhoneModelId:null})
    requestMock.post.mockReturnValue(apiResponse({lastError:null}))
    await expect(refreshMarketWatchProjection()).resolves.toEqual({lastError:null})
    expect(requestMock.post).toHaveBeenLastCalledWith('/market-watch/refresh')
  })
})
