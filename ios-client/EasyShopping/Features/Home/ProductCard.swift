import SwiftUI

/// 商品卡片（首页瀑布流 / 搜索结果共用）
struct ProductCard: View {
    let item: ProductCardItem

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RemoteImage(urlString: item.mainImage)
                .frame(height: 150)
                .frame(maxWidth: .infinity)
                .clipped()
                .cornerRadius(8)

            Text(item.name ?? "")
                .font(.subheadline)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundColor(.primary)

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                AmountText(amount: item.price, font: .headline)
                if let original = item.originalPrice,
                   let price = item.price,
                   original > price {
                    AmountText(amount: original, font: .caption, color: .secondary, strikethrough: true)
                }
            }

            if let sales = item.salesCount {
                Text("\(sales) 人已购")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(8)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }
}
