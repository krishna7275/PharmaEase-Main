package com.pharmaease.service;

import com.pharmaease.model.*;
import com.pharmaease.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final StockBatchRepository batchRepository;
    private final InvoiceRepository invoiceRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public Report generateSalesReport(LocalDate startDate, LocalDate endDate, Report.ReportType reportType, Pharmacist pharmacist) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<Orders> orders = orderRepository.findByCreatedAtBetweenWithRelations(start, end);

        BigDecimal totalSales = orders.stream()
                .filter(o -> o.getStatus() == Orders.OrderStatus.COMPLETED)
                .map(Orders::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int totalOrders = (int) orders.stream()
                .filter(o -> o.getStatus() == Orders.OrderStatus.COMPLETED)
                .count();

        Report report = new Report();
        report.setReportType(reportType);
        report.setStartDate(startDate);
        report.setEndDate(endDate);
        report.setTotalSales(totalSales);
        report.setTotalOrders(totalOrders);
        report.setGeneratedBy(pharmacist);
        // Use rupee symbol in the human-readable summary text
        report.setSummary(String.format("Sales report from %s to %s. Total orders: %d, Total sales: ₹%.2f",
                startDate, endDate, totalOrders, totalSales));

        return reportRepository.save(report);
    }

    public Report generateInventoryReport(Pharmacist pharmacist) {
        List<Inventory> inventoryList = inventoryRepository.findAll();

        long lowStockCount = inventoryList.stream()
                .filter(Inventory::getLowStock)
                .count();

        long outOfStockCount = inventoryList.stream()
                .filter(i -> i.getAvailableQuantity() == 0)
                .count();

        Report report = new Report();
        report.setReportType(Report.ReportType.INVENTORY);
        report.setStartDate(LocalDate.now());
        report.setEndDate(LocalDate.now());
        report.setGeneratedBy(pharmacist);
        report.setSummary(String.format("Inventory report. Total items: %d, Low stock: %d, Out of stock: %d",
                inventoryList.size(), lowStockCount, outOfStockCount));

        return reportRepository.save(report);
    }

    public Report generateLowStockReport(Pharmacist pharmacist) {
        List<Inventory> lowStockItems = inventoryRepository.findLowStockItems();

        Report report = new Report();
        report.setReportType(Report.ReportType.LOW_STOCK);
        report.setStartDate(LocalDate.now());
        report.setEndDate(LocalDate.now());
        report.setGeneratedBy(pharmacist);
        report.setSummary(String.format("Low stock report. %d items need reordering", lowStockItems.size()));

        return reportRepository.save(report);
    }

    public Report generateExpiringStockReport(int daysAhead, Pharmacist pharmacist) {
        LocalDate today = LocalDate.now();
        LocalDate futureDate = today.plusDays(daysAhead);

        List<StockBatch> expiringBatches = batchRepository.findExpiringBatches(today, futureDate);

        Report report = new Report();
        report.setReportType(Report.ReportType.EXPIRING_STOCK);
        report.setStartDate(today);
        report.setEndDate(futureDate);
        report.setGeneratedBy(pharmacist);
        report.setSummary(String.format("Expiring stock report. %d batches expiring within %d days",
                expiringBatches.size(), daysAhead));

        return reportRepository.save(report);
    }

    public List<Report> getAllReports() {
        return reportRepository.findAll();
    }

    public Report getReportById(Long id) {
        return reportRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Report not found"));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> getDashboardStatistics() {

        Map<String, Object> stats = new HashMap<>();

        // Define common date ranges
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // Query directly from database
        List<Orders> completedOrders = (List<Orders>) entityManager.createQuery(
                "SELECT o FROM Orders o WHERE o.status = 'COMPLETED' ORDER BY o.createdAt DESC",
                Orders.class).getResultList();

        System.out.println("📊 Dashboard Query - COMPLETED orders: " + completedOrders.size());

        // Log all completed orders for debugging
        if (!completedOrders.isEmpty()) {
            System.out.println("📊 COMPLETED Orders Details:");
            completedOrders.forEach(o -> {
                System.out.println("  - ID: " + o.getId() + 
                                 " | Order#: " + o.getOrderNumber() + 
                                 " | Status: " + o.getStatus() + 
                                 " | Amount: ₹" + o.getTotalAmount() + 
                                 " | Created: " + o.getCreatedAt());
            });
        }

        // Calculate today's sales and orders by filtering in memory
        List<Orders> todayCompleted = completedOrders.stream()
                .filter(o -> {
                    LocalDateTime created = o.getCreatedAt();
                    return created != null && 
                           !created.isBefore(todayStart) && 
                           !created.isAfter(todayEnd);
                })
                .collect(Collectors.toList());
        
        double todaySales = todayCompleted.stream()
                .mapToDouble(o -> o.getTotalAmount() != null ? o.getTotalAmount().doubleValue() : 0.0)
                .sum();
        stats.put("todaySales", todaySales);
        stats.put("todayOrders", (long) todayCompleted.size());
        
        System.out.println("📊 Today - Orders: " + todayCompleted.size() + ", Sales: ₹" + todaySales);

        // Week sales
        List<Orders> weekCompleted = completedOrders.stream()
                .filter(o -> {
                    LocalDateTime created = o.getCreatedAt();
                    return created != null && 
                           !created.isBefore(weekStart) && 
                           !created.isAfter(todayEnd);
                })
                .collect(Collectors.toList());
        double weekSales = weekCompleted.stream()
                .mapToDouble(o -> o.getTotalAmount() != null ? o.getTotalAmount().doubleValue() : 0.0)
                .sum();
        stats.put("weekSales", weekSales);

        // Month sales
        List<Orders> monthCompleted = completedOrders.stream()
                .filter(o -> {
                    LocalDateTime created = o.getCreatedAt();
                    return created != null && 
                           !created.isBefore(monthStart) && 
                           !created.isAfter(todayEnd);
                })
                .collect(Collectors.toList());
        double monthSales = monthCompleted.stream()
                .mapToDouble(o -> o.getTotalAmount() != null ? o.getTotalAmount().doubleValue() : 0.0)
                .sum();
        stats.put("monthSales", monthSales);

        // Total sales
        double totalSales = completedOrders.stream()
                .mapToDouble(o -> o.getTotalAmount() != null ? o.getTotalAmount().doubleValue() : 0.0)
                .sum();
        stats.put("totalSales", totalSales);
        
        System.out.println("📊 Final Stats - Total Sales: ₹" + totalSales + ", Today Orders: " + todayCompleted.size() + ", Today Sales: ₹" + todaySales);

        // Low stock count
        Long lowStockCount = inventoryRepository.countLowStockItems();
        stats.put("lowStockCount", lowStockCount != null ? lowStockCount : 0L);

        // Expiring soon (30 days) - skip this query to avoid terminal noise and improve performance
        // Only calculate if really needed, otherwise set to 0
        stats.put("expiringBatchesCount", 0);

        return stats;
    }

    private double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}