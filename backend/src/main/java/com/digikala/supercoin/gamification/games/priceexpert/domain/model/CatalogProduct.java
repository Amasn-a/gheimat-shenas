package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

/** A product snapshot in one catalog version: the displayed default variant and its lowest valid price (R-40). */
public record CatalogProduct(long dkp, long variantId, String title, String displayTitle, String imageUrl, long priceToman) {}
