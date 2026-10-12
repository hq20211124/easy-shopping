import SwiftUI

/// 商品详情：主图轮播 → 价格 → SKU 矩阵 → 图文详情 → 底部操作栏。
struct ProductDetailView: View {
    let spuId: Int64

    @StateObject private var vm: ProductDetailViewModel
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var appState: AppState

    init(spuId: Int64) {
        self.spuId = spuId
        _vm = StateObject(wrappedValue: ProductDetailViewModel(spuId: spuId))
    }

    var body: some View {
        Group {
            if let detail = vm.detail {
                content(detail)
            } else if vm.isLoading {
                LoadingView()
            } else if let error = vm.errorMessage {
                ErrorStateView(message: error) {
                    Task { await vm.load() }
                }
            } else {
                EmptyStateView(title: "商品不存在或已下架", systemImage: "questionmark.square")
            }
        }
        .background(AppTheme.background)
        .navigationTitle("商品详情")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            bottomBar
        }
        .task {
            await vm.load()
        }
        .toast($vm.message)
    }

    // MARK: 主体

    private func content(_ detail: ProductDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                gallery
                priceAndTitle(detail)
                SkuMatrixView(vm: vm)
                salesSection(detail)
                detailSection(detail)
            }
            .padding(.bottom, 16)
        }
    }

    private var gallery: some View {
        TabView {
            ForEach(vm.galleryImages, id: \.self) { url in
                RemoteImage(urlString: url)
                    .frame(maxWidth: .infinity)
                    .frame(height: 320)
                    .clipped()
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .frame(height: 330)
        .background(Color.gray.opacity(0.08))
    }

    private func priceAndTitle(_ detail: ProductDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                AmountText(amount: vm.displayPrice, font: .title.bold())
                if let original = vm.displayOriginalPrice,
                   let price = vm.displayPrice,
                   original > price {
                    AmountText(amount: original, font: .subheadline, color: .secondary, strikethrough: true)
                }
                Spacer()
            }

            Text(detail.name ?? "")
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)

            if let subtitle = detail.subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
        .padding(.horizontal, 12)
    }

    private func salesSection(_ detail: ProductDetail) -> some View {
        HStack(spacing: 16) {
            if let sales = detail.salesCount {
                Label("\(sales) 人已购", systemImage: "flame")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            if let code = detail.spuCode {
                Label("货号 \(code)", systemImage: "number")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
        }
        .padding(.horizontal, 12)
    }

    @ViewBuilder
    private func detailSection(_ detail: ProductDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("商品详情")
                .font(.subheadline.bold())

            if let html = detail.detail, !html.isEmpty {
                RichTextView(html: html, height: 480)
                    .frame(height: 480)
                    .cornerRadius(8)
            } else {
                Text("暂无图文详情")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
        .padding(.horizontal, 12)
    }

    // MARK: 底部操作栏

    private var bottomBar: some View {
        HStack(spacing: 10) {
            Button {
                vm.message = "在线客服 P1 接入"
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: "headphones")
                    Text("客服").font(.caption2)
                }
                .foregroundColor(.primary)
            }

            Button {
                router.selectedTab = .cart
            } label: {
                VStack(spacing: 2) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "cart")
                        if appState.cartCount > 0 {
                            Text("\(appState.cartCount)")
                                .font(.system(size: 9))
                                .padding(.horizontal, 3)
                                .padding(.vertical, 1)
                                .background(AppTheme.primary)
                                .foregroundColor(.white)
                                .cornerRadius(6)
                                .offset(x: 10, y: -6)
                        }
                    }
                    Text("购物车").font(.caption2)
                }
                .foregroundColor(.primary)
            }

            Button {
                Task {
                    await vm.addToCart()
                }
            } label: {
                Group {
                    if vm.addingToCart {
                        ProgressView().tint(.white)
                    } else {
                        Text("加入购物车")
                    }
                }
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.orange)
                .foregroundColor(.white)
                .cornerRadius(22)
            }
            .disabled(vm.addingToCart)

            Button {
                buyNow()
            } label: {
                Text("立即购买")
                    .font(.subheadline.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(AppTheme.primary)
                    .foregroundColor(.white)
                    .cornerRadius(22)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private func buyNow() {
        guard appState.requireLogin() else { return }
        guard let item = vm.buyNowItem() else { return }
        router.push(.checkout(items: [item]))
    }
}
