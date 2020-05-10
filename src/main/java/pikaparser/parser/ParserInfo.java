package pikaparser.parser;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import pikaparser.clause.Clause;
import pikaparser.clause.Terminal;
import pikaparser.grammar.Grammar;
import pikaparser.memotable.Match;
import pikaparser.memotable.MemoTable;

public class ParserInfo {

    private static final char NON_ASCII_CHAR = '■';

    private static void getConsumedChars(Match match, BitSet consumedChars) {
        for (int i = match.memoKey.startPos, ii = match.memoKey.startPos + match.len; i < ii; i++) {
            consumedChars.set(i);
        }
        Match[] subClauseMatches = match.subClauseMatches;
        if (subClauseMatches != null) {
            for (int i = 0; i < subClauseMatches.length; i++) {
                Match subClauseMatch = subClauseMatches[i];
                getConsumedChars(subClauseMatch, consumedChars);
            }
        }
    }

    private static void replaceNonASCII(String str, StringBuilder buf) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            buf.append(c < 32 || c > 126 ? NON_ASCII_CHAR : c);
        }
    }

    private static String replaceNonASCII(String str) {
        StringBuilder buf = new StringBuilder();
        replaceNonASCII(str, buf);
        return buf.toString();
    }

    public static void printClauses(Grammar grammar) {
        for (int i = grammar.allClauses.size() - 1; i >= 0; --i) {
            var clause = grammar.allClauses.get(i);
            System.out.println(String.format("%3d : %s", i, clause.toStringWithRuleNames()));
        }
    }

    private static int findCycleDepth(Match match,
            Map<Integer, Map<Integer, Map<Integer, Match>>> cycleDepthToMatches) {
        var cycleDepth = 0;
        for (var subClauseMatch : match.subClauseMatches) {
            var subClauseIsInDifferentCycle = //
                    match.memoKey.clause.clauseIdx <= subClauseMatch.memoKey.clause.clauseIdx;
            var subClauseMatchDepth = findCycleDepth(subClauseMatch, cycleDepthToMatches);
            cycleDepth = Math.max(cycleDepth,
                    subClauseIsInDifferentCycle ? subClauseMatchDepth + 1 : subClauseMatchDepth);
        }
        var matchesForDepth = cycleDepthToMatches.get(cycleDepth);
        if (matchesForDepth == null) {
            matchesForDepth = new TreeMap<>(Collections.reverseOrder());
            cycleDepthToMatches.put(cycleDepth, matchesForDepth);
        }
        var matchesForClauseIdx = matchesForDepth.get(match.memoKey.clause.clauseIdx);
        if (matchesForClauseIdx == null) {
            matchesForClauseIdx = new TreeMap<>();
            matchesForDepth.put(match.memoKey.clause.clauseIdx, matchesForClauseIdx);
        }
        matchesForClauseIdx.put(match.memoKey.startPos, match);
        return cycleDepth;
    }

    public static void printMatchTree(String toplevelRuleName, Grammar grammar, MemoTable memoTable, String input,
            BitSet consumedChars) {
        if (grammar.allClauses.size() == 0) {
            throw new IllegalArgumentException("Grammar is empty");
        }

        // Get all nonoverlapping matches of the toplevel rule
        Map<Integer, Map<Integer, Map<Integer, Match>>> cycleDepthToMatches = new TreeMap<>(
                Collections.reverseOrder());
        var maxCycleDepth = 0;
        for (var topLevelMatch : grammar.getNonOverlappingMatches(toplevelRuleName, memoTable)) {
            var cycleDepth = findCycleDepth(topLevelMatch, cycleDepthToMatches);
            maxCycleDepth = Math.max(maxCycleDepth, cycleDepth);
        }

        List<Map<Integer, Match>> matchesForRow = new ArrayList<>();
        List<Clause> clauseForRow = new ArrayList<>();
        for (var matchesForDepth : cycleDepthToMatches.values()) {
            for (var matchesForClauseIdxEnt : matchesForDepth.entrySet()) {
                clauseForRow.add(grammar.allClauses.get(matchesForClauseIdxEnt.getKey()));
                matchesForRow.add(matchesForClauseIdxEnt.getValue());
            }
        }

        // Set up row labels
        var rowLabel = new StringBuilder[clauseForRow.size()];
        var marginWidth = 0;
        for (var i = 0; i < clauseForRow.size(); i++) {
            var clause = clauseForRow.get(i);
            var clauseIdx = clause.clauseIdx;
            rowLabel[i] = new StringBuilder();
            rowLabel[i].append(String.format("%3d", clauseIdx) + " : ");
            if (clause instanceof Terminal) {
                rowLabel[i].append("[terminal] ");
            }
            if (clause.canMatchZeroChars) {
                rowLabel[i].append("[canMatchZeroChars] ");
            }
            rowLabel[i].append(clause.toStringWithRuleNames());
            marginWidth = Math.max(marginWidth, rowLabel[i].length() + 2);
        }
        for (var i = 0; i < clauseForRow.size(); i++) {
            while (rowLabel[i].length() < marginWidth) {
                rowLabel[i].append(' ');
            }
        }
        var emptyRowLabel = new StringBuilder();
        for (int i = 0; i < marginWidth; i++) {
            emptyRowLabel.append(' ');
        }
        var edgeMarkers = new StringBuilder();
        for (int i = 0, ii = (input.length() + 1) * 2 + 1; i < ii; i++) {
            edgeMarkers.append(' ');
        }

        // Add tree structure to right of row label
        for (var row = 0; row < clauseForRow.size(); row++) {
            var matches = matchesForRow.get(row);

            StringBuilder treeChars = new StringBuilder();
            treeChars.append(edgeMarkers);
            for (var ent : matches.entrySet()) {
                var match = ent.getValue();
                var startIdx = match.memoKey.startPos;
                var endIdx = startIdx + match.len;

                for (var i = startIdx; i <= endIdx; i++) {
                    char chrLeft = edgeMarkers.charAt(i * 2);
                    treeChars.setCharAt(i * 2, i == startIdx ? (chrLeft == '│' ? '├' : '┌')
                            : i == endIdx ? (chrLeft == '│' ? '┤' : '┐') : (chrLeft == '│' ? '┼' : '─'));
                    if (i < endIdx) {
                        treeChars.setCharAt(i * 2 + 1, '─');
                    }
                }
            }
            System.out.print(emptyRowLabel);
            System.out.println(treeChars);

            for (var ent : matches.entrySet()) {
                var match = ent.getValue();
                var startIdx = match.memoKey.startPos;
                var endIdx = startIdx + match.len;
                edgeMarkers.setCharAt(startIdx * 2, '│');
                edgeMarkers.setCharAt(endIdx * 2, '│');
            }
            treeChars.setLength(0);
            treeChars.append(edgeMarkers);
            for (var ent : matches.entrySet()) {
                var match = ent.getValue();
                var startIdx = match.memoKey.startPos;
                var endIdx = startIdx + match.len;
                for (int i = startIdx; i < endIdx; i++) {
                    treeChars.setCharAt(i * 2 + 1, input.charAt(i));
                }
            }
            System.out.print(rowLabel[row]);
            System.out.println(treeChars);
        }

        // Print input index digits
        for (int j = 0; j < marginWidth; j++) {
            System.out.print(' ');
        }
        System.out.print(' ');
        for (int i = 0; i < input.length(); i++) {
            System.out.print(i % 10);
            System.out.print(' ');
        }
        System.out.println();

        // Print input string
        for (int i = 0; i < marginWidth; i++) {
            System.out.print(' ');
        }
        System.out.print(' ');
        var str = replaceNonASCII(input);
        for (int i = 0; i < input.length(); i++) {
            System.out.print(str.charAt(i));
            System.out.print(' ');
        }
        System.out.println();

        // Show consumed chars
        if (consumedChars != null) {
            for (int i = 0; i < marginWidth; i++) {
                System.out.print(' ');
            }
            for (int i = 0; i < input.length(); i++) {
                System.out.print(consumedChars.get(i) ? "^" : " ");
            }
            System.out.println();
        }
    }

    /** Print syntax errors obtained from {@link Grammar#getSyntaxErrors(MemoTable, String, String...)}. */
    public static void printSyntaxErrors(TreeMap<Integer, Entry<Integer, String>> syntaxErrors) {
        if (!syntaxErrors.isEmpty()) {
            System.out.println("\nSYNTAX ERRORS:\n");
            for (var ent : syntaxErrors.entrySet()) {
                var startPos = ent.getKey();
                var endPos = ent.getValue().getKey();
                var syntaxErrStr = ent.getValue().getValue();
                // TODO: show line numbers
                System.out.println(startPos + "+" + (endPos - startPos) + " : " + replaceNonASCII(syntaxErrStr));
            }
        }
    }

    public static void printParseResult(String topLevelRuleName, Grammar grammar, MemoTable memoTable, String input,
            String[] syntaxCoverageRuleNames, boolean showAllMatches) {
        // Print parse tree, and find which characters were consumed and which weren't
        BitSet consumedChars = new BitSet(input.length() + 1);

        System.out.println();
        System.out.println("Clauses:");
        printClauses(grammar);

        // Print memo table
        System.out.println();
        System.out.println("Match tree for rule " + topLevelRuleName + ":");
        printMatchTree(topLevelRuleName, grammar, memoTable, input, consumedChars);

        // Print all matches for each clause
        for (Clause clause : grammar.allClauses) {
            var matches = memoTable.getAllMatches(clause);
            if (!matches.isEmpty()) {
                System.out.println("\n====================================\n\nMatches for "
                        + clause.toStringWithRuleNames() + " :");
                // Get toplevel AST node label(s), if present
                String astNodeLabel = "";
                if (clause.rules != null) {
                    for (var rule : clause.rules) {
                        if (rule.astNodeLabel != null) {
                            if (!astNodeLabel.isEmpty()) {
                                astNodeLabel += ",";
                            }
                            astNodeLabel += rule.astNodeLabel;
                        }
                    }
                }
                var prevEndPos = -1;
                for (int i = 0; i < matches.size(); i++) {
                    var match = matches.get(i);
                    // Indent matches that overlap with previous longest match
                    var overlapsPrevMatch = match.memoKey.startPos < prevEndPos;
                    if (!overlapsPrevMatch || showAllMatches) {
                        var indent = overlapsPrevMatch ? "    " : "";
                        System.out.println();
                        match.printTreeView(input, indent, astNodeLabel.isEmpty() ? null : astNodeLabel, true);
                    }
                    int newEndPos = match.memoKey.startPos + match.len;
                    if (newEndPos > prevEndPos) {
                        prevEndPos = newEndPos;
                    }
                }
            }
        }

        var topLevelRule = grammar.ruleNameWithPrecedenceToRule.get(topLevelRuleName);
        if (topLevelRule != null) {
            var topLevelRuleClause = topLevelRule.clause;
            var topLevelMatches = memoTable.getNonOverlappingMatches(topLevelRuleClause);
            if (!topLevelMatches.isEmpty()) {
                for (int i = 0; i < topLevelMatches.size(); i++) {
                    var topLevelMatch = topLevelMatches.get(i);
                    getConsumedChars(topLevelMatch, consumedChars);
                }
            }
            if (!topLevelMatches.isEmpty()) {
                System.out.println("\n====================================\n\nFinal AST for rule \""
                        + topLevelRuleName + "\":");
                var topLevelASTNodeName = topLevelRule.astNodeLabel;
                if (topLevelASTNodeName == null) {
                    topLevelASTNodeName = "<root>";
                }
                for (int i = 0; i < topLevelMatches.size(); i++) {
                    var topLevelMatch = topLevelMatches.get(i);
                    var ast = topLevelMatch.toAST(topLevelASTNodeName, input);
                    if (ast != null) {
                        System.out.println();
                        System.out.println(ast.toString());
                    }
                }
            } else {
                System.out.println("\nToplevel rule \"" + topLevelRuleName + "\" did not match anything");
            }
        } else {
            System.out.println("\nToplevel rule \"" + topLevelRuleName + "\" does not exist");
        }

        var syntaxErrors = grammar.getSyntaxErrors(memoTable, input, syntaxCoverageRuleNames);
        if (!syntaxErrors.isEmpty()) {
            printSyntaxErrors(syntaxErrors);
        }

        System.out.println("\nNum match objects created: " + memoTable.numMatchObjectsCreated);
        System.out.println("Num match objects memoized:  " + memoTable.numMatchObjectsMemoized);
    }
}
