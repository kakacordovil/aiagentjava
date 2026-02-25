import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MiniReActAgent {

    // ====== Dados do loop ======

    public record Message(String role, String content, Instant at) {}

    public record Action(String toolName, String input) {}

    public record StepResult(Action action, String observation) {}

    // ====== Tool interface ======

    public interface Tool {
        String name();
        String description();
        String run(String input) throws Exception;
    }

    // ====== Tools de exemplo ======

    public static class CalculatorTool implements Tool {
        @Override public String name() { return "calculator"; }
        @Override public String description() { return "Faz contas simples: add, sub, mul, div. Ex: '12 * 3'."; }

        @Override
        public String run(String input) {
            // Bem simples e limitado, só pra demo
            Pattern p = Pattern.compile("\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*");
            Matcher m = p.matcher(input);
            if (!m.matches()) return "Erro: formato inválido. Use algo como '12 * 3'.";

            double a = Double.parseDouble(m.group(1));
            String op = m.group(2);
            double b = Double.parseDouble(m.group(3));

            double res = switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? Double.NaN : a / b;
                default -> Double.NaN;
            };

            if (Double.isNaN(res)) return "Erro: operação inválida (talvez divisão por zero).";
            // Se for inteiro, mostra sem .0
            if (Math.floor(res) == res) return String.valueOf((long) res);
            return String.valueOf(res);
        }
    }

    public static class DictionaryTool implements Tool {
        private final Map<String, String> dict = Map.of(
            "react", "ReAct = Reasoning + Acting: loop de raciocínio e ações com observações.",
            "rewoo", "ReWOO = Reasoning Without Observation: planeja tudo e executa sem replanejar no meio."
        );

        @Override public String name() { return "dictionary"; }
        @Override public String description() { return "Explica termos curtos. Input: uma palavra."; }

        @Override
        public String run(String input) {
            String key = input.trim().toLowerCase(Locale.ROOT);
            return dict.getOrDefault(key, "Não encontrei no dicionário demo.");
        }
    }

    // ====== “LLM” (Planner) ======
    // Aqui é onde você pluga um modelo real depois.
    // Por enquanto, é uma heurística: decide uma Action ou decide responder final.
    public interface Planner {
        /**
         * Decide a próxima ação ou retorna null para indicar "vou responder final".
         */
        Action nextAction(String goal, List<Message> memory, Map<String, Tool> tools);
        /**
         * Gera a resposta final ao usuário.
         */
        String finalAnswer(String goal, List<Message> memory);
    }

    public static class HeuristicPlanner implements Planner {
        @Override
        public Action nextAction(String goal, List<Message> memory, Map<String, Tool> tools) {
            String g = goal.toLowerCase(Locale.ROOT);

            // Se tem conta, usa calculator.
            if (g.matches(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) {
                // extrai a expressão mais “provável”
                Matcher m = Pattern.compile("(\\-?\\d+(?:\\.\\d+)?\\s*[+\\-*/]\\s*\\-?\\d+(?:\\.\\d+)?)").matcher(goal);
                if (m.find()) return new Action("calculator", m.group(1));
            }

            // Se pergunta por react ou rewoo
            if (g.contains("react")) return new Action("dictionary", "react");
            if (g.contains("rewoo")) return new Action("dictionary", "rewoo");

            // Se já usamos uma tool 1x e não achou nada, para e responde.
            long toolUses = memory.stream().filter(msg -> msg.role.equals("tool")).count();
            if (toolUses >= 1) return null;

            // Default: sem tool
            return null;
        }

        @Override
        public String finalAnswer(String goal, List<Message> memory) {
            // Monta resposta com base no que observou
            Optional<Message> lastTool = memory.stream()
                .filter(m -> m.role.equals("tool"))
                .reduce((a, b) -> b);

            if (lastTool.isPresent()) {
                return "Pelo que observei usando a ferramenta: " + lastTool.get().content;
            }
            return "Não precisei usar ferramentas. Minha resposta é: depende do objetivo, mas posso te ajudar a quebrar isso em passos.";
        }
    }

    // ====== Agente ======

    public static class Agent {
        private final Planner planner;
        private final Map<String, Tool> tools = new HashMap<>();
        private final List<Message> memory = new ArrayList<>();

        public Agent(Planner planner) {
            this.planner = planner;
        }

        public Agent registerTool(Tool tool) {
            tools.put(tool.name(), tool);
            return this;
        }

        public String run(String goal, int maxSteps) {
            memory.add(new Message("user", goal, Instant.now()));

            for (int step = 1; step <= maxSteps; step++) {
                // THOUGHT/PLAN (implícito no planner)
                Action action = planner.nextAction(goal, memory, tools);

                if (action == null) {
                    // FINAL
                    String answer = planner.finalAnswer(goal, memory);
                    memory.add(new Message("assistant", answer, Instant.now()));
                    return answer;
                }

                // ACTION
                Tool tool = tools.get(action.toolName());
                if (tool == null) {
                    String obs = "Erro: tool '" + action.toolName() + "' não registrada.";
                    memory.add(new Message("tool", obs, Instant.now()));
                    continue;
                }

                try {
                    String observation = tool.run(action.input());
                    // OBSERVATION
                    memory.add(new Message("tool", tool.name() + " → " + observation, Instant.now()));
                } catch (Exception e) {
                    memory.add(new Message("tool", tool.name() + " → erro: " + e.getMessage(), Instant.now()));
                }
            }

            String fallback = "PareI por limite de passos. Posso continuar com mais steps se você quiser.";
            memory.add(new Message("assistant", fallback, Instant.now()));
            return fallback;
        }

        public List<Message> memory() { return Collections.unmodifiableList(memory); }
    }

    // ====== Demo ======

    public static void main(String[] args) {
        Agent agent = new Agent(new HeuristicPlanner())
            .registerTool(new CalculatorTool())
            .registerTool(new DictionaryTool());

        System.out.println(agent.run("Quanto é 12 * 3?", 5));
        System.out.println(agent.run("Explica ReAct rapidinho", 5));
        System.out.println(agent.run("E ReWOO?", 5));
    }
}
