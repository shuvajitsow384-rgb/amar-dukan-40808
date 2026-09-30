package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.local.entities.BarcodeVariant
import com.example.data.local.entities.Product
import com.example.ui.theme.*
import com.example.utils.BengaliReceiptTranslator
import com.example.utils.ImageSyncHelper
import com.example.utils.LanguageManager
import com.example.viewmodel.CartItem
import com.example.viewmodel.StoreViewModel
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

@Composable
fun PosVariantSelectorModal(
    product: Product,
    viewModel: StoreViewModel,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onOpenLooseWeightModal: () -> Unit
) {
    val context = LocalContext.current
    val variants = remember(product) { product.getBarcodeVariants() }
    val cartItems = viewModel.cartItems

    // Map of variantBarcode -> CartItem
    val variantCartMap by remember(cartItems) {
        derivedStateOf {
            cartItems
                .filter { it.product.id == product.id && it.isVariant }
                .associateBy { it.variantBarcode ?: "" }
        }
    }

    val standardCartItem by remember(cartItems) {
        derivedStateOf {
            cartItems.firstOrNull { it.product.id == product.id && !it.isVariant && !it.isFreeGift }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 520.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!product.imageUri.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceWarm),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageSyncHelper.getImageModel(product.imageUri),
                                contentDescription = product.getDisplayName(isBn),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = product.getDisplayName(isBn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = StorePrimary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isBn) "${variants.size}টি প্যাকেট সাইজ" else "${variants.size} Pack Sizes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StorePrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "• Stock: ${product.getFormattedStockDisplay(isBn)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (product.currentStock <= product.lowStockThreshold) StoreRedAlert else TextMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isBn) "ভ্যারিয়েন্ট নির্বাচন করুন (প্যাকেট / সাইজ):" else "Select Barcode Variant / Pack Size:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StorePrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Variants list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(variants, key = { it.id.ifBlank { it.barcode } }) { variant ->
                        val cartItem = variantCartMap[variant.barcode]
                        val qtyInCart = cartItem?.quantity ?: 0.0
                        val baseDeduct = product.calculateVariantBaseDeduction(variant, 1.0)
                        val effMrp = variant.getEffectiveMrp()
                        val hasDiscount = variant.hasDiscount()

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (qtyInCart > 0.0) StoreGreenProfit.copy(alpha = 0.06f) else SurfaceWarm
                            ),
                            border = BorderStroke(
                                width = if (qtyInCart > 0.0) 1.5.dp else 1.dp,
                                color = if (qtyInCart > 0.0) StoreGreenProfit else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Variant info column
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = variant.getShortLabel().ifBlank { "${variant.quantity} ${variant.unitType}" },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDark
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = StorePrimary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = variant.getPackagingDisplay(isBn),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = StorePrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    // Pricing & MRP savings
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "₹%.2f".format(variant.price),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = StoreGreenProfit
                                        )
                                        if (hasDiscount) {
                                            Text(
                                                text = "₹%.2f".format(effMrp),
                                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                            Surface(
                                                color = Color(0xFFEA580C).copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(3.dp)
                                            ) {
                                                Text(
                                                    text = "%.0f%% OFF".format(variant.getDiscountPercent()),
                                                    color = Color(0xFFEA580C),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Code: ${variant.barcode} • 1 pack = %.2f %s".format(baseDeduct, product.unitType),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Add or Stepper Actions
                                if (qtyInCart <= 0.0) {
                                    Button(
                                        onClick = {
                                            val added = viewModel.addVariantToCart(product, variant, 1.0)
                                            if (!added) {
                                                Toast.makeText(context, if (isBn) "পর্যাপ্ত স্টক নেই!" else "Insufficient stock for this pack!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "যোগ করুন" else "Add",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                } else {
                                    // Stepper
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        FilledIconButton(
                                            onClick = {
                                                val existingIdx = cartItems.indexOfFirst {
                                                    it.product.id == product.id && it.variantBarcode == variant.barcode
                                                }
                                                if (existingIdx >= 0) {
                                                    if (qtyInCart <= 1.0) {
                                                        viewModel.removeFromCart(existingIdx)
                                                    } else {
                                                        viewModel.updateCartItemQuantity(existingIdx, qtyInCart - 1.0)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(30.dp),
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = StoreRedAlert.copy(alpha = 0.15f))
                                        ) {
                                            Icon(
                                                imageVector = if (qtyInCart <= 1.0) Icons.Default.DeleteOutline else Icons.Default.Remove,
                                                contentDescription = "Decrease",
                                                tint = StoreRedAlert,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        Surface(
                                            color = Color.White,
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.5f)),
                                            modifier = Modifier.height(30.dp).widthIn(min = 34.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(horizontal = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (qtyInCart % 1.0 == 0.0) qtyInCart.toInt().toString() else qtyInCart.toString(),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StoreGreenProfit
                                                )
                                            }
                                        }

                                        FilledIconButton(
                                            onClick = {
                                                val added = viewModel.addVariantToCart(product, variant, 1.0)
                                                if (!added) {
                                                    Toast.makeText(context, if (isBn) "পর্যাপ্ত স্টক নেই!" else "Insufficient stock for this pack!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(30.dp),
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = StoreGreenProfit)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Loose / Standard Option Row
                OutlinedCard(
                    onClick = {
                        onDismiss()
                        onOpenLooseWeightModal()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Scale,
                                contentDescription = null,
                                tint = StorePrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isBn) "খুচরা / কাস্টম ওজন বিক্রি" else "Loose / Custom Weight Sale",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                                Text(
                                    text = "₹%.2f / %s".format(product.sellingPrice, BengaliReceiptTranslator.translateUnit(product.unitType, isBn)),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        if (standardCartItem != null) {
                            Surface(
                                color = StoreGreenProfit,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "In Bill: ${if (standardCartItem!!.quantity % 1.0 == 0.0) standardCartItem!!.quantity.toInt() else standardCartItem!!.quantity} ${standardCartItem!!.unitType}",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            Text(
                                text = if (isBn) "ওজন বাছুন ›" else "Choose Qty ›",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Text(
                        text = if (isBn) "সম্পন্ন" else "Done",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
