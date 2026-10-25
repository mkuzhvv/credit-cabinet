package com.creditcabinet.catalog.api;

import com.creditcabinet.catalog.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/products")
    @Operation(operationId = "listProducts", summary = "Получить действующий каталог")
    public ProductList products() {
        return new ProductList(catalog.getProducts());
    }
}
