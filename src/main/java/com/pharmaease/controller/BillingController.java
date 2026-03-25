package com.pharmaease.controller;

import com.pharmaease.model.*;
import com.pharmaease.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final OrderService orderService;
    private final CustomerService customerService;
    private final MedicineService medicineService;
    private final PharmacistService pharmacistService;
    private final BillingService billingService;

    @GetMapping
    public String billingPage(Model model) {
        model.addAttribute("customers", customerService.getActiveCustomers());
        model.addAttribute("medicines", medicineService.getActiveMedicines());
        return "billing";
    }

    @PostMapping("/create-order")
    public String createOrder(@RequestParam(required = false) Long customerId,
                              @RequestParam(required = false) String paymentMethod,
                              @RequestParam(required = false, defaultValue = "0") BigDecimal discount,
                              @RequestParam Map<String, String> allParams,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        try {
            System.out.println("=== CREATING NEW SALE ===");
            
            // Create new order
            Orders order = new Orders();
            
            // Set customer if provided
            if (customerId != null && customerId > 0) {
                Customer customer = customerService.getCustomerById(customerId);
                order.setCustomer(customer);
                System.out.println("Customer: " + customer.getName());
            } else {
                System.out.println("Walk-in customer");
            }

            // Set pharmacist from authenticated user
            String email = authentication.getName();
            Pharmacist pharmacist = pharmacistService.getPharmacistByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Pharmacist not found"));
            order.setPharmacist(pharmacist);
            System.out.println("Pharmacist: " + pharmacist.getName());

            // Set payment method
            if (paymentMethod != null && !paymentMethod.isEmpty()) {
                try {
                    order.setPaymentMethod(Orders.PaymentMethod.valueOf(paymentMethod));
                } catch (IllegalArgumentException e) {
                    order.setPaymentMethod(Orders.PaymentMethod.CASH);
                }
            } else {
                order.setPaymentMethod(Orders.PaymentMethod.CASH);
            }
            System.out.println("Payment Method: " + order.getPaymentMethod());

            // Set discount
            order.setDiscount(discount != null ? discount : BigDecimal.ZERO);

            // Build order items from form parameters
            List<OrderItem> orderItems = new ArrayList<>();
            int index = 0;
            while (allParams.containsKey("orderItems[" + index + "].medicine.id")) {
                String medicineIdStr = allParams.get("orderItems[" + index + "].medicine.id");
                String quantityStr = allParams.get("orderItems[" + index + "].quantity");
                String unitPriceStr = allParams.get("orderItems[" + index + "].unitPrice");
                
                if (medicineIdStr != null && !medicineIdStr.isEmpty() && 
                    quantityStr != null && !quantityStr.isEmpty() &&
                    unitPriceStr != null && !unitPriceStr.isEmpty()) {
                    try {
                        Long medicineId = Long.parseLong(medicineIdStr);
                        Integer quantity = Integer.parseInt(quantityStr);
                        BigDecimal unitPrice = new BigDecimal(unitPriceStr);
                        
                        if (quantity > 0 && unitPrice.compareTo(BigDecimal.ZERO) > 0) {
                            OrderItem item = new OrderItem();
                            Medicine medicine = medicineService.getMedicineById(medicineId);
                            item.setMedicine(medicine);
                            item.setQuantity(quantity);
                            item.setUnitPrice(unitPrice);
                            item.setOrder(order);
                            item.setTotalPrice(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                            orderItems.add(item);
                            System.out.println("Added item: " + medicine.getName() + " x " + quantity);
                        }
                    } catch (Exception e) {
                        System.err.println("Error parsing item at index " + index + ": " + e.getMessage());
                    }
                }
                index++;
            }

            // Validate that we have at least one order item
            if (orderItems.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please add at least one medicine to the order");
                return "redirect:/billing";
            }

            order.setOrderItems(orderItems);
            System.out.println("Total items: " + orderItems.size());
            
            // CRITICAL: Set order as COMPLETED and PAID (billing = immediate sale)
            order.setStatus(Orders.OrderStatus.COMPLETED);
            order.setPaid(true);
            
            // Create order - will auto-generate invoice and update inventory
            Orders createdOrder = orderService.createOrder(order);
            
            System.out.println("✅ Order created successfully: " + createdOrder.getOrderNumber());
            System.out.println("Order ID: " + createdOrder.getId());
            System.out.println("Status: " + createdOrder.getStatus());
            System.out.println("Total Amount: ₹" + createdOrder.getTotalAmount());

            // CRITICAL: Get the order ID and verify it exists before redirecting
            Long orderId = createdOrder.getId();
            if (orderId == null) {
                throw new RuntimeException("Order ID is null after creation");
            }
            
            // Verify order exists in database before redirecting
            try {
                Orders verifiedOrder = orderService.getOrderById(orderId);
                System.out.println("✅ Verified order exists: " + verifiedOrder.getOrderNumber());
                System.out.println("✅ Order status: " + verifiedOrder.getStatus());
                System.out.println("✅ Order has " + (verifiedOrder.getOrderItems() != null ? verifiedOrder.getOrderItems().size() : 0) + " items");
            } catch (Exception e) {
                System.err.println("⚠️ Warning: Could not verify order immediately: " + e.getMessage());
                // Continue anyway - might be a timing issue
            }

            System.out.println("✅ Redirecting to invoice for order ID: " + orderId);
            redirectAttributes.addFlashAttribute("success", "Sale completed successfully! Order #" + createdOrder.getOrderNumber());
            return "redirect:/billing/invoice/" + orderId;

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ Error creating order: " + e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Error creating order: " + e.getMessage());
            return "redirect:/billing";
        }
    }

    @GetMapping("/invoice/{orderId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String viewInvoice(@PathVariable Long orderId, Model model, RedirectAttributes redirectAttributes) {
        try {
            Orders order = orderService.getOrderById(orderId);

            // Defensive: make sure Thymeleaf never touches a Hibernate lazy proxy.
            // We initialize and then replace the collection with a plain ArrayList so it stays usable
            // even if the session is closed before view rendering completes.
            List<OrderItem> items = order.getOrderItems();
            if (items != null) {
                for (OrderItem item : items) {
                    if (item.getMedicine() != null) {
                        item.getMedicine().getName(); // Trigger lazy load
                    }
                }
                int size = items.size(); // Trigger full collection initialization
                System.out.println("✅ Initialized " + size + " order items for invoice");

                // Replace Hibernate-managed collection with a plain list
                order.setOrderItems(new ArrayList<>(items));
            }

            // EAGER anyway, but keep initialization explicit
            if (order.getCustomer() != null) order.getCustomer().getName();
            if (order.getPharmacist() != null) order.getPharmacist().getName();
            
            try {
                Invoice invoice = billingService.getInvoiceByOrder(order);
                model.addAttribute("invoice", invoice);
            } catch (Exception e) {
                System.err.println("Invoice not found for order: " + orderId + " - " + e.getMessage());
                // Invoice might not exist yet, that's okay
                model.addAttribute("invoice", null);
            }
            
            model.addAttribute("order", order);
            return "invoice";
            
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("error", "Order not found: " + e.getMessage());
            return "redirect:/billing";
        }
    }

    @GetMapping("/invoices")
    public String listInvoices(Model model) {
        model.addAttribute("invoices", billingService.getAllInvoices());
        return "invoices";
    }
}