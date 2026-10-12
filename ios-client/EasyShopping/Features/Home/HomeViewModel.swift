import Foundation

@MainActor
final class HomeViewModel: ObservableObject {

    @Published var products: [ProductCardItem] = []
    @Published var hotWords: [HotWord] = []
    @Published var isLoading = false
    @Published var isLoadingMore = false
    @Published var errorMessage: String?

    private var page = 1
    private var hasMore = true

    private static let pageSize = 20

    /// 进入首页：首屏为空才拉取，热搜词同理
    func onAppear() async {
        if products.isEmpty {
            await load()
        }
        if hotWords.isEmpty {
            await loadHotWords()
        }
    }

    /// 首屏 / 下拉刷新
    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result: PageResult<ProductCardItem> = try await APIClient.shared.request(
                "/api/v1/products",
                query: [
                    URLQueryItem(name: "page", value: "1"),
                    URLQueryItem(name: "pageSize", value: String(Self.pageSize))
                ]
            )
            products = result.list
            page = 1
            hasMore = Int64(result.list.count) < result.total
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// 上拉加载更多（page 分页）
    func loadMore() async {
        guard hasMore, !isLoading, !isLoadingMore else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }

        let next = page + 1
        do {
            let result: PageResult<ProductCardItem> = try await APIClient.shared.request(
                "/api/v1/products",
                query: [
                    URLQueryItem(name: "page", value: String(next)),
                    URLQueryItem(name: "pageSize", value: String(Self.pageSize))
                ]
            )
            let existing = Set(products.map { $0.spuId })
            let appended = result.list.filter { !existing.contains($0.spuId) }
            products.append(contentsOf: appended)
            page = next
            if appended.isEmpty || Int64(products.count) >= result.total {
                hasMore = false
            }
        } catch {
            // 翻页失败静默，用户可上拉重试
        }
    }

    /// 滚动位置是否接近末尾（触发预取下一页）
    func shouldLoadMore(_ item: ProductCardItem) -> Bool {
        guard let index = products.firstIndex(where: { $0.id == item.id }) else { return false }
        return index >= products.count - 4
    }

    private func loadHotWords() async {
        do {
            let words: [HotWord] = try await APIClient.shared.request(
                "/api/v1/search/hot",
                query: [URLQueryItem(name: "limit", value: "10")]
            )
            hotWords = words
        } catch {
            // 热搜词失败不影响首页
        }
    }
}
