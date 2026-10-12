import SwiftUI

/// 搜索页：输入即联想（300ms 防抖），回车/点建议提交；结果按 cursor 分页。
struct SearchView: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var vm = SearchViewModel()

    @FocusState private var focused: Bool
    /// 已提交的关键词：等于当前输入时展示结果，否则展示联想
    @State private var submittedKeyword: String?

    private let columns = [
        GridItem(.adaptive(minimum: 76), spacing: 8)
    ]

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            Divider()
            content
        }
        .background(AppTheme.background)
        .navigationTitle("搜索")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await vm.onAppear()
        }
        .task(id: vm.keyword) {
            // 联想防抖：输入变化即取消上一次（300ms）
            guard !vm.keyword.isEmpty, submittedKeyword != vm.keyword else { return }
            try? await Task.sleep(nanoseconds: 300_000_000)
            await vm.fetchSuggestions()
        }
        .onChange(of: router.searchKeyword) { value in
            // 首页热搜词 / 其它模块投喂关键词
            guard let value = value, !value.isEmpty else { return }
            submit(value)
            router.searchKeyword = nil
        }
    }

    // MARK: 视图分发

    private var trimmedKeyword: String {
        vm.keyword.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @ViewBuilder
    private var content: some View {
        if trimmedKeyword.isEmpty {
            discoverySection
        } else if submittedKeyword == trimmedKeyword {
            resultSection
        } else {
            suggestionSection
        }
    }

    // MARK: 搜索栏

    private var searchBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("搜索商品名称", text: $vm.keyword)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit {
                        submit(nil)
                    }
                if !vm.keyword.isEmpty {
                    Button {
                        vm.keyword = ""
                        submittedKeyword = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(AppTheme.card)
            .cornerRadius(18)

            if submittedKeyword != nil {
                Button("取消") {
                    cancel()
                }
                .font(.subheadline)
                .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // MARK: 搜索发现（历史 + 热搜）

    private var discoverySection: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if !vm.history.isEmpty {
                    sectionHeader(title: "搜索历史") {
                        Button {
                            vm.clearHistory()
                        } label: {
                            Image(systemName: "trash")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    tagGrid(items: vm.history) { text in
                        submit(text)
                    } onDelete: { text in
                        vm.removeHistory(text)
                    }
                }

                if !vm.hotWords.isEmpty {
                    sectionHeader(title: "热搜榜", action: nil)
                    tagGrid(items: vm.hotWords.map { $0.keyword }) { text in
                        submit(text)
                    } onDelete: { _ in }
                }

                if vm.history.isEmpty && vm.hotWords.isEmpty {
                    EmptyStateView(title: "输入关键词开始搜索", systemImage: "magnifyingglass")
                }
            }
            .padding(12)
        }
    }

    // MARK: 联想建议

    private var suggestionSection: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(vm.suggestions) { item in
                    Button {
                        if item.isProduct, let productId = item.productId {
                            router.push(.productDetail(spuId: productId), on: .search)
                        } else if let text = item.text {
                            submit(text)
                        }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: item.isProduct ? "shippingbox" : "clock.arrow.circlepath")
                                .foregroundColor(.secondary)
                                .frame(width: 18)
                            Text(item.text ?? "")
                                .font(.subheadline)
                                .foregroundColor(.primary)
                                .lineLimit(1)
                            Spacer()
                            Image(systemName: "arrow.up.left")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.leading, 42)
                }

                if vm.suggestions.isEmpty && !vm.isLoading {
                    EmptyStateView(title: "无匹配建议，回车直接搜索", systemImage: "text.magnifyingglass")
                }
            }
        }
    }

    // MARK: 搜索结果

    private var resultSection: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if !vm.results.isEmpty {
                    Text("共 \(vm.total) 件商品")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                if let error = vm.errorMessage, vm.results.isEmpty {
                    ErrorStateView(message: error) {
                        Task { await vm.search(reset: true) }
                    }
                } else if vm.isLoading && vm.results.isEmpty {
                    LoadingView()
                } else if vm.results.isEmpty {
                    EmptyStateView(title: "未找到相关商品",
                                   actionTitle: "返回搜索发现",
                                   action: { submittedKeyword = nil })
                } else {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(vm.results) { item in
                            NavigationLink(value: Route.productDetail(spuId: item.spuId)) {
                                ProductCard(item: item)
                            }
                            .buttonStyle(.plain)
                            .onAppear {
                                if vm.shouldLoadMore(item) {
                                    Task { await vm.search(reset: false) }
                                }
                            }
                        }
                    }

                    if vm.isLoadingMore {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                        .padding(.vertical, 12)
                    }
                }
            }
            .padding(12)
        }
    }

    // MARK: 组件

    private func sectionHeader(title: String, action: (() -> Void)?) -> some View {
        HStack {
            Text(title)
                .font(.subheadline.bold())
            Spacer()
            if let action = action {
                Button(action: action) {
                    Image(systemName: "trash")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private func tagGrid(items: [String],
                         onSelect: @escaping (String) -> Void,
                         onDelete: @escaping (String) -> Void) -> some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { text in
                HStack(spacing: 4) {
                    Button {
                        onSelect(text)
                    } label: {
                        Text(text)
                            .font(.caption)
                            .lineLimit(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(AppTheme.card)
                            .cornerRadius(12)
                            .foregroundColor(.primary)
                    }
                    .buttonStyle(.plain)

                    Button {
                        onDelete(text)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 8))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: 动作

    private func submit(_ text: String?) {
        let value = (text ?? vm.keyword).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        vm.keyword = value
        submittedKeyword = value
        focused = false
        vm.addHistory(value)
        Task { await vm.search(reset: true) }
    }

    private func cancel() {
        submittedKeyword = nil
        vm.keyword = ""
        vm.results = []
        vm.suggestions = []
        vm.errorMessage = nil
        focused = false
    }
}
