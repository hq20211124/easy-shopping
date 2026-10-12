import SwiftUI

/// 首页：吸顶搜索入口 + 热搜词 + 商品瀑布流。
/// 说明：iOS BFF 聚合接口（plans ios-backend 11.1.2 `GET /api/v1/bff/ios/home`）P0 未实现，
/// 首屏降级为「商品列表 + 热搜词」两个接口（见 README「P0 降级清单」）。
struct HomeView: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var vm = HomeViewModel()

    private let columns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10)
    ]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                searchBar

                if !vm.hotWords.isEmpty {
                    hotWordSection
                }

                if let error = vm.errorMessage, vm.products.isEmpty {
                    ErrorStateView(message: error) {
                        Task { await vm.load() }
                    }
                } else if vm.isLoading && vm.products.isEmpty {
                    LoadingView()
                } else if vm.products.isEmpty {
                    EmptyStateView(title: "暂无商品")
                } else {
                    productGrid
                }
            }
            .padding(12)
        }
        .background(AppTheme.background)
        .navigationTitle("易购")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await vm.load()
        }
        .task {
            await vm.onAppear()
        }
    }

    // MARK: 子视图

    private var searchBar: some View {
        Button {
            router.selectedTab = .search
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                Text("搜索商品")
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(AppTheme.card)
            .cornerRadius(20)
        }
        .buttonStyle(.plain)
    }

    private var hotWordSection: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(vm.hotWords) { word in
                    Button {
                        router.searchKeyword = word.keyword
                        router.selectedTab = .search
                    } label: {
                        Text(word.keyword)
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(AppTheme.card)
                            .cornerRadius(12)
                            .foregroundColor(.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var productGrid: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            ForEach(vm.products) { item in
                NavigationLink(value: Route.productDetail(spuId: item.spuId)) {
                    ProductCard(item: item)
                }
                .buttonStyle(.plain)
                .onAppear {
                    if vm.shouldLoadMore(item) {
                        Task { await vm.loadMore() }
                    }
                }
            }
        }
    }
}
