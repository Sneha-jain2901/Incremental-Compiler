import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

public class CompilerUI extends JFrame {
    private JComboBox<String> fileSelector;
    private DefaultComboBoxModel<String> fileListModel;
    private JTextArea outputArea;
    private JTree astTree;
    private JPanel graphPanel;
    private JButton parseButton;
    private JButton compileButton;
    private JButton clearButton;
    private boolean isUpdatingSelection = false;
    
    public CompilerUI() {
        initializeUI();
        initializeCompiler();
    }

    private void initializeUI() {
        setTitle("Java Incremental Compiler");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        fileListModel = new DefaultComboBoxModel<>();
        fileSelector = new JComboBox<>(fileListModel);
        fileSelector.setPreferredSize(new Dimension(250, 30));
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        controlPanel.add(new JLabel("Select File:"));
        controlPanel.add(fileSelector);
        
        parseButton = new JButton("Parse Source");
        compileButton = new JButton("Compile Changes");
        clearButton = new JButton("Clear Output");
        
        controlPanel.add(parseButton);
        controlPanel.add(compileButton);
        controlPanel.add(clearButton);

        outputArea = new JTextArea();
        outputArea.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);

        astTree = new JTree();
        JScrollPane astScroll = new JScrollPane(astTree);
        astScroll.setBorder(BorderFactory.createTitledBorder("AST Viewer"));

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

        setupEventHandlers();
    }

    private void setupEventHandlers() {
        parseButton.addActionListener(e -> {
            try {
                IncrementalCompiler.parseSourceFolder();
                refreshFileList();
                graphPanel.repaint();
                outputArea.append("Source files parsed successfully\n");
            } catch (Exception ex) {
                outputArea.append("Error parsing source: " + ex.getMessage() + "\n");
            }
        });

        compileButton.addActionListener(e -> {
            try {
                List<File> changedFiles = IncrementalCompiler.detectChangedFiles();
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
            } catch (Exception ex) {
                outputArea.append("Compilation failed: " + ex.getMessage() + "\n");
            }
        });

        clearButton.addActionListener(e -> outputArea.setText(""));
        
        fileSelector.addActionListener(e -> {
             if (!isUpdatingSelection) { 
            String selected = (String) fileSelector.getSelectedItem();
            if (selected != null && !selected.isEmpty()) {
                displayAST(selected);
            }}
        });
    }

    private void initializeCompiler() {
        try {
            IncrementalCompiler.loadHashes();
            refreshFileList();
            outputArea.append("Compiler initialized\n");
        } catch (Exception e) {
            outputArea.append("Initialization error: " + e.getMessage() + "\n");
        }
    }

    private void refreshFileList() {
        isUpdatingSelection = true;
        fileListModel.removeAllElements();
        File[] files = IncrementalCompiler.SRC_DIR.listFiles((d, name) -> name.endsWith(".java"));
        
        if (files != null && files.length > 0) {
            for (File file : files) {
                fileListModel.addElement(file.getName());
            }
            fileSelector.setSelectedIndex(0);
        }
        isUpdatingSelection = false;
    }

    private void displayAST(String filename) {
        try {
            File file = new File(IncrementalCompiler.SRC_DIR, filename);
            CompilationUnit cu = IncrementalCompiler.parseFileForAST(file);
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(filename);
            
            cu.getPackageDeclaration().ifPresent(pkg -> 
                root.add(new DefaultMutableTreeNode("Package: " + pkg.getNameAsString())));
            
            DefaultMutableTreeNode importsNode = new DefaultMutableTreeNode("Imports");
            cu.getImports().forEach(imp -> importsNode.add(new DefaultMutableTreeNode(imp.getNameAsString())));
            if (importsNode.getChildCount() > 0) root.add(importsNode);
            
            cu.getTypes().forEach(type -> {
                DefaultMutableTreeNode typeNode = new DefaultMutableTreeNode(type.getNameAsString());
                
                type.getFields().forEach(field -> {
                    String fieldInfo = field.getVariables().get(0).getNameAsString() + 
                                      ": " + field.getElementType();
                    typeNode.add(new DefaultMutableTreeNode(fieldInfo));
                });
                
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
            
            outputArea.append("Displaying AST for: " + filename + "\n");
        } catch (IOException e) {
            outputArea.append("Error parsing " + filename + ": " + e.getMessage() + "\n");
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
        
        int centerX = graphPanel.getWidth() / 2;
        int centerY = graphPanel.getHeight() / 2;
        int radius = Math.min(centerX, centerY) - 70;
        
        List<String> nodes = new ArrayList<>(graph.keySet());
        Map<String, Point> nodePositions = new HashMap<>();
        
        double angleStep = 2 * Math.PI / nodes.size();
        for (int i = 0; i < nodes.size(); i++) {
            int x = centerX + (int)(radius * Math.cos(i * angleStep));
            int y = centerY + (int)(radius * Math.sin(i * angleStep));
            nodePositions.put(nodes.get(i), new Point(x, y));
        }
        
        g2.setStroke(new BasicStroke(1.5f));
        for (String source : graph.keySet()) {
            Point p1 = nodePositions.get(source);
            if (p1 == null) continue;
            
            for (String target : graph.get(source)) {
                Point p2 = nodePositions.get(target);
                if (p2 == null) continue;
                
                double dx = p2.x - p1.x;
                double dy = p2.y - p1.y;
                double dist = Math.sqrt(dx*dx + dy*dy);
                double scale = 15 / dist;
                
                int startX = p1.x + (int)(dx * scale);
                int startY = p1.y + (int)(dy * scale);
                int endX = p2.x - (int)(dx * scale);
                int endY = p2.y - (int)(dy * scale);
                
                g2.setColor(new Color(100, 100, 100, 150));
                g2.drawLine(startX, startY, endX, endY);
                
                drawArrowHead(g2, startX, startY, endX, endY);
            }
        }
        
        for (Map.Entry<String, Point> entry : nodePositions.entrySet()) {
            Point p = entry.getValue();
            
            g2.setColor(new Color(70, 130, 180));
            g2.fillOval(p.x - 15, p.y - 15, 30, 30);
            g2.setColor(Color.BLACK);
            g2.drawOval(p.x - 15, p.y - 15, 30, 30);
            
            String label = entry.getKey().replace(".java", "");
            if (label.length() > 8) {
                label = label.substring(0, 6) + "..";
            }
            g2.setColor(Color.WHITE);
            g2.drawString(label, p.x - g2.getFontMetrics().stringWidth(label)/2, p.y + 5);
        }
        
        g2.setColor(Color.BLACK);
        g2.drawString("Dependency Direction:", 20, 20);
        drawArrowHead(g2, 150, 15, 200, 15);
        g2.drawString("A → B means A depends on B", 220, 20);
    }

    private void drawArrowHead(Graphics2D g2, int x1, int y1, int x2, int y2) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int arrowLength = 12;
        int arrowWidth = 8;
        
        Point arrowPoint1 = new Point(
            (int) (x2 - arrowLength * Math.cos(angle - Math.PI/6)),
            (int) (y2 - arrowLength * Math.sin(angle - Math.PI/6))
        );
        Point arrowPoint2 = new Point(
            (int) (x2 - arrowLength * Math.cos(angle + Math.PI/6)),
            (int) (y2 - arrowLength * Math.sin(angle + Math.PI/6))
        );
        
        int[] xPoints = {x2, arrowPoint1.x, arrowPoint2.x};
        int[] yPoints = {y2, arrowPoint1.y, arrowPoint2.y};
        
        g2.setColor(new Color(178, 34, 34));
        g2.fillPolygon(xPoints, yPoints, 3);
        g2.setColor(Color.BLACK);
        g2.drawPolygon(xPoints, yPoints, 3);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CompilerUI ui = new CompilerUI();
            ui.setVisible(true);
        });
    }
}