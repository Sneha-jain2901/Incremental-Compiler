
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;  // Explicit import for List interface
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

public class CompilerUI extends JFrame {
    private JComboBox<String> fileSelector;
    private DefaultComboBoxModel<String> fileListModel;
    private JTextArea outputArea;
    private JTree astTree;
    private JPanel graphPanel;
    
    public CompilerUI() {
        initializeUI();
        parseSource(); // Load files on startup
    }

    private void initializeUI() {
        setTitle("Java Incremental Compiler");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // File selector setup
        fileListModel = new DefaultComboBoxModel<>();
        fileSelector = new JComboBox<>(fileListModel);
        fileSelector.setPreferredSize(new Dimension(250, 30));
        
        // Control Panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        controlPanel.add(new JLabel("Select File:"));
        controlPanel.add(fileSelector);
        
        JButton parseButton = new JButton("Parse Source");
        JButton compileButton = new JButton("Compile Changes");
        JButton clearButton = new JButton("Clear Output");
        
        controlPanel.add(parseButton);
        controlPanel.add(compileButton);
        controlPanel.add(clearButton);

        // Output area
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);

        // AST Tree
        astTree = new JTree();
        JScrollPane astScroll = new JScrollPane(astTree);
        astScroll.setBorder(BorderFactory.createTitledBorder("AST Viewer"));

        // Dependency Graph
        graphPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawDependencyGraph(g);
            }
        };
        graphPanel.setPreferredSize(new Dimension(400, 400));
        JScrollPane graphScroll = new JScrollPane(graphPanel);
        graphScroll.setBorder(BorderFactory.createTitledBorder("Dependency Graph"));

        // Layout
        JSplitPane leftRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        rightSplit.setTopComponent(astScroll);
        rightSplit.setBottomComponent(graphScroll);
        rightSplit.setDividerLocation(400);

        leftRightSplit.setLeftComponent(outputScroll);
        leftRightSplit.setRightComponent(rightSplit);
        leftRightSplit.setDividerLocation(600);

        add(controlPanel, BorderLayout.NORTH);
        add(leftRightSplit, BorderLayout.CENTER);

        // Event Listeners
        parseButton.addActionListener(e -> parseSource());
        compileButton.addActionListener(e -> compileChanges());
        clearButton.addActionListener(e -> outputArea.setText(""));
        
        fileSelector.addActionListener(e -> {
            String selected = (String) fileSelector.getSelectedItem();
            if (selected != null) {
                displayAST(new File(IncrementalCompiler.SRC_DIR, selected));
            }
        });
    }

    private void parseSource() {
    outputArea.append("Scanning source directory...\n");
    File[] files = IncrementalCompiler.SRC_DIR.listFiles((d, name) -> name.endsWith(".java"));
    
    fileListModel.removeAllElements();
    
    if (files != null && files.length > 0) {
        outputArea.append("Found " + files.length + " Java files:\n");
        for (File file : files) {
            fileListModel.addElement(file.getName());
            outputArea.append("- " + file.getName() + "\n");
        }
        // Auto-select first file
        fileSelector.setSelectedIndex(0); 
        
        // ADD THIS: Parse files to build dependency graph
        try {
            IncrementalCompiler.parseSourceFolder();
            outputArea.append("Dependency graph built successfully\n");
        } catch (Exception e) {
            outputArea.append("Error building dependency graph: " + e.getMessage() + "\n");
        }
    } else {
        outputArea.append("No Java files found in src directory!\n");
    }
    
    // Force graph panel to repaint
    graphPanel.repaint();
    
    // Display AST for selected file if any
    String selected = (String) fileSelector.getSelectedItem();
    if (selected != null) {
        displayAST(new File(IncrementalCompiler.SRC_DIR, selected));
    }
}
    private void displayAST(File file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(file.getName());
            
            // Package
            cu.getPackageDeclaration().ifPresent(pkg -> 
                root.add(new DefaultMutableTreeNode("Package: " + pkg.getNameAsString())));
            
            // Imports
            DefaultMutableTreeNode importsNode = new DefaultMutableTreeNode("Imports");
            cu.getImports().forEach(imp -> importsNode.add(new DefaultMutableTreeNode(imp.getNameAsString())));
            if (importsNode.getChildCount() > 0) root.add(importsNode);
            
            // Classes/Interfaces
            cu.getTypes().forEach(type -> {
                DefaultMutableTreeNode typeNode = new DefaultMutableTreeNode(type.getNameAsString());
                
                // Fields
                type.getFields().forEach(field -> {
                    String fieldInfo = field.getVariables().get(0).getNameAsString() + 
                                      ": " + field.getElementType();
                    typeNode.add(new DefaultMutableTreeNode(fieldInfo));
                });
                
                // Methods
                type.getMethods().forEach(method -> {
                    typeNode.add(new DefaultMutableTreeNode(
                        method.getNameAsString() + method.getSignature()));
                });
                
                root.add(typeNode);
            });
            
            astTree.setModel(new DefaultTreeModel(root));
            for (int i = 0; i < astTree.getRowCount(); i++) {
                astTree.expandRow(i);
            }
            
            outputArea.append("Displaying AST for: " + file.getName() + "\n");
        } catch (IOException e) {
            outputArea.append("Error parsing " + file.getName() + ": " + e.getMessage() + "\n");
        }
    }

   private void drawDependencyGraph(Graphics g) {
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    
    Map<String, Set<String>> graph = IncrementalCompiler.dependencyGraph;
    if (graph.isEmpty()) {
        g2.drawString("Parse source files to see dependencies", 50, 50);
        return;
    }
    
    // Circular layout with more space
    int centerX = graphPanel.getWidth() / 2;
    int centerY = graphPanel.getHeight() / 2;
    int radius = Math.min(centerX, centerY) - 70;
    
    List<String> nodes = new ArrayList<>(graph.keySet());
    Map<String, Point> nodePositions = new HashMap<>();
    
    // Position nodes
    double angleStep = 2 * Math.PI / nodes.size();
    for (int i = 0; i < nodes.size(); i++) {
        int x = centerX + (int)(radius * Math.cos(i * angleStep));
        int y = centerY + (int)(radius * Math.sin(i * angleStep));
        nodePositions.put(nodes.get(i), new Point(x, y));
    }
    
    // Draw edges with clear directional arrows
    g2.setStroke(new BasicStroke(1.5f));
    for (String source : graph.keySet()) {
        Point p1 = nodePositions.get(source);
        if (p1 == null) continue;
        
        for (String target : graph.get(source)) {
            Point p2 = nodePositions.get(target);
            if (p2 == null) continue;
            
            // Calculate line direction and adjust endpoints to node boundaries
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double dist = Math.sqrt(dx*dx + dy*dy);
            double scale = 15 / dist; // Node radius is 15
            
            // Calculate where the line should start and end (at node edges)
            int startX = p1.x + (int)(dx * scale);
            int startY = p1.y + (int)(dy * scale);
            int endX = p2.x - (int)(dx * scale);
            int endY = p2.y - (int)(dy * scale);
            
            // Draw the connecting line
            g2.setColor(new Color(100, 100, 100, 150)); // Semi-transparent gray
            g2.drawLine(startX, startY, endX, endY);
            
            // Draw directional arrow
            drawArrowHead(g2, startX, startY, endX, endY);
        }
    }
    
    // Draw nodes with improved styling
    for (Map.Entry<String, Point> entry : nodePositions.entrySet()) {
        Point p = entry.getValue();
        
        // Node circle
        g2.setColor(new Color(70, 130, 180)); // Steel blue
        g2.fillOval(p.x - 15, p.y - 15, 30, 30);
        g2.setColor(Color.BLACK);
        g2.drawOval(p.x - 15, p.y - 15, 30, 30);
        
        // Node label (shortened filename)
        String label = entry.getKey().replace(".java", "");
        if (label.length() > 8) {
            label = label.substring(0, 6) + "..";
        }
        g2.setColor(Color.WHITE);
        g2.drawString(label, p.x - g2.getFontMetrics().stringWidth(label)/2, p.y + 5);
    }
    
    // Add legend
    g2.setColor(Color.BLACK);
    g2.drawString("Dependency Direction:", 20, 20);
    drawArrowHead(g2, 150, 15, 200, 15);
    g2.drawString("A → B means A depends on B", 220, 20);
}

// Improved arrow head drawing
private void drawArrowHead(Graphics2D g2, int x1, int y1, int x2, int y2) {
    double angle = Math.atan2(y2 - y1, x2 - x1);
    int arrowLength = 12;
    int arrowWidth = 8;
    
    // Calculate points for arrow head
    Point arrowPoint1 = new Point(
        (int) (x2 - arrowLength * Math.cos(angle - Math.PI/6)),
        (int) (y2 - arrowLength * Math.sin(angle - Math.PI/6))
    );
    Point arrowPoint2 = new Point(
        (int) (x2 - arrowLength * Math.cos(angle + Math.PI/6)),
        (int) (y2 - arrowLength * Math.sin(angle + Math.PI/6))
    );
    
    // Create polygon for arrow head
    int[] xPoints = {x2, arrowPoint1.x, arrowPoint2.x};
    int[] yPoints = {y2, arrowPoint1.y, arrowPoint2.y};
    
    // Draw and fill the arrow head
    g2.setColor(new Color(178, 34, 34)); // Firebrick red
    g2.fillPolygon(xPoints, yPoints, 3);
    g2.setColor(Color.BLACK);
    g2.drawPolygon(xPoints, yPoints, 3);
}
    private void compileChanges() {
        outputArea.append("\nDetecting changes...\n");
        try {
            java.util.List<File> changedFiles = IncrementalCompiler.detectChangedFiles();
            Set<File> toCompile = IncrementalCompiler.getFilesToCompile(changedFiles);
            
            if (!toCompile.isEmpty()) {
                outputArea.append("Compiling " + toCompile.size() + " files:\n");
                toCompile.forEach(f -> outputArea.append("- " + f.getName() + "\n"));
                
                IncrementalCompiler.compileFiles(toCompile);
                IncrementalCompiler.updateHashes(toCompile);
                IncrementalCompiler.saveHashes();
                outputArea.append("Compilation successful\n");
            } else {
                outputArea.append("No changes detected\n");
            }
        } catch (Exception e) {
            outputArea.append("Compilation failed: " + e.getMessage() + "\n");
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CompilerUI ui = new CompilerUI();
            ui.setVisible(true);
        });
    }
}
