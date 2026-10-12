import Foundation

@MainActor
final class SearchViewModel: ObservableObject {

    @Published var keyword = ""
    @Published var results: [ProductCardItem] = []
    @Published var total: Int64 = 0
    @Published var suggestions: [SuggestionItem] = []
    @Published var hotWords: [HotWord] = []
    @Published var history: [String] = []
    @Published var isLoading = false
    @Published var isLoadingMore = false
    @Published var errorMessage: String?

    private var cursor: String?
    private var hasMore = false

    private static let historyKey = "com.easyshopping.search.history"
    private static let maxHistoryCount = 20
    private static let pageSize = 20

    // MARK: 页面数据

    func onAppear() async {
        history = UserDefaults.standard.stringArray(forKey: Self.historyKey) ?? []
        if hotWords.isEmpty {
            do {
                let words: [HotWord] = try await APIClient.shared.request(
                    "/api/v1/search/hot",
                    query: [URLQueryItem(name: "limit", value: "10")]
                )
                hotWords = words
            } catch {
                // 热搜失败不影响搜索
            }
        }
    }

    // MARK: 搜索（cursor 分页）

    /// reset=true 时不带 cursor 回到首页（换排序/换关键词必须回首页，见 core-search README §4）
    func search(reset: Bool) async {
        let q = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }

        if reset {
            results = []
            cursor = nil
            hasMore = true
            errorMessage = nil
        }
        guard hasMore else { return }

        isLoading = results.isEmpty
        isLoadingMore = !results.isEmpty
        defer {
            isLoading = false
            isLoadingMore = false
        }

        var query = [
            URLQueryItem(name: "q", value: q),
            URLQueryItem(name: "pageSize", value: String(Self.pageSize))
        ]
        if let cursor = cursor {
            query.append(URLQueryItem(name: "cursor", value: cursor))
        }

        do {
            let response: SearchResponse = try await APIClient.shared.request(
                "/api/v1/search/products",
                query: query
            )
            let list = response.list ?? []
            let existing = Set(results.map { $0.spuId })
            results.append(contentsOf: list.filter { !existing.contains($0.spuId) })
            total = response.total ?? Int64(results.count)
            cursor = response.nextCursor
            hasMore = response.nextCursor != nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func shouldLoadMore(_ item: ProductCardItem) -> Bool {
        guard let index = results.firstIndex(where: { $0.id == item.id }) else { return false }
        return index >= results.count - 4
    }

    // MARK: 联想建议

    func fetchSuggestions() async {
        let q = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else {
            suggestions = []
            return
        }
        do {
            let response: SuggestionResponse = try await APIClient.shared.request(
                "/api/v1/search/suggestions",
                query: [
                    URLQueryItem(name: "q", value: q),
                    URLQueryItem(name: "limit", value: "10")
                ]
            )
            suggestions = response.suggestions ?? []
        } catch {
            suggestions = []
        }
    }

    // MARK: 历史（本地，最多 20 条）

    func addHistory(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var list = history.filter { $0 != trimmed }
        list.insert(trimmed, at: 0)
        if list.count > Self.maxHistoryCount {
            list = Array(list.prefix(Self.maxHistoryCount))
        }
        history = list
        UserDefaults.standard.set(list, forKey: Self.historyKey)
    }

    func removeHistory(_ text: String) {
        history.removeAll { $0 == text }
        UserDefaults.standard.set(history, forKey: Self.historyKey)
    }

    func clearHistory() {
        history = []
        UserDefaults.standard.removeObject(forKey: Self.historyKey)
    }
}
