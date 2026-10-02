# Package: ai.redouble.examples.scopes

[An agent that stays inside its case](../scope/PACKAGE.md) bound a flow to one axis, its
customer. Real work overlaps several: a support shift works one customer's case AND only
the email channel, and when one order of that case is disputed, the escalation should
touch that order and nothing else. This example binds a flow to two unrelated axes at
once, then narrows one of them partway through, and shows every binding holding at every
depth. No model is involved; the bindings refuse code and model alike.

## A narrowing is an extension

The case axis is `CaseScope`, a class holding the customer. One order of the case is a
narrowing of that axis, and in Java a narrowing is a subclass:

<!-- sample: OrderScope.java#order -->
```java
public class OrderScope extends CaseScope {
    final String orderNumber;

    public OrderScope(String customerId, String orderNumber) {
        super(customerId);
        this.orderNumber = orderNumber;
    }

    @Override
    public boolean matches(Scope candidate) {
        if (!super.matches(candidate)) {
            return false;
        }
        // A plain case claim carries no order to judge; only a claim of this depth is narrowed
        return !(candidate instanceof OrderScope other) || orderNumber.equals(other.orderNumber);
    }
```

Extending is what relates the two: a case binding recognizes an order claim and judges its
customer, and an order binding judges a plain case claim the same way. The third axis,
`ChannelScope`, shares no class with either, which is exactly what makes it independent:
its binding judges channel claims and lets everything else through.

## Claiming several axes at once

The note tool's input names the order and the channel, so it claims both. Implementing two
markers leaves the class with two default `scope()` methods, and the compiler forces the
override; the composite is how one claim answers for both axes:

<!-- sample: FileNote.java#claim -->
```java
public class FileNote implements OrderScoped, ChannelScoped {
    @LLMRequired
    @LLMDescription("The customer the note is about, like C-100")
    private String customerId;
    @LLMRequired
    @LLMDescription("The order the note is about, like A-1042")
    private String orderNumber;
    @LLMRequired
    @LLMDescription("The channel the exchange happened on: email or phone")
    private String channel;
    @LLMRequired
    @LLMDescription("The note to file")
    private String note;

    @Override
    public Scope scope() {
        return new CompositeScope(OrderScoped.super.scope(), ChannelScoped.super.scope());
    }
```

## Two bindings on the shift, a third from some point on

The shift is bound to the case and the channel the same way, two markers and a composite:

<!-- sample: EmailShiftDoer.java#shift -->
```java
@ToolName("email_shift")
@ToolDescription("Work one customer's case on the email channel")
public class EmailShiftDoer extends AbstractDoer<String, List<String>> implements CaseScoped, ChannelScoped {
    private final String customerId;

    public EmailShiftDoer(Identifiable parent, String customerId) {
        super(parent);
        this.customerId = customerId;
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }

    @Override
    public String getChannel() {
        return "email";
    }

    @Override
    public Scope scope() {
        return new CompositeScope(CaseScoped.super.scope(), ChannelScoped.super.scope());
    }
```

Partway through, the shift escalates one disputed order by spawning `DisputeDoer`. The
escalation declares only the narrowing; the case and channel bindings of whoever spawned
it ride in through the runtime and keep holding:

<!-- sample: DisputeDoer.java#dispute -->
```java
@ToolName("dispute")
@ToolDescription("Work one disputed order: file the notes of the dispute")
public class DisputeDoer extends AbstractDoer<String, List<String>> implements OrderScoped {
    private final String customerId;
    private final String orderNumber;

    public DisputeDoer(Identifiable parent, String customerId, String orderNumber) {
        super(parent);
        this.customerId = customerId;
        this.orderNumber = orderNumber;
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }

    @Override
    public String getOrderNumber() {
        return orderNumber;
    }
```

## What the run shows

`OverlappingScopes` runs the shift for C-100. It files five notes, and each outcome names
the axis that decided it:

1. On `A-1005` via email, before the escalation: **filed**. Any order of the case is
   workable on the shift.
2. On the disputed `A-1002` via email, inside the escalation: **filed**. Inside every
   binding.
3. On `A-1005` via email, inside the escalation: **refused by the order binding**. The
   same claim that was filed at step 1 is refused now, which is the narrowing: from the
   escalation on, the case axis means one order.
4. On `A-1002` via phone, inside the escalation: **refused by the channel binding**. The
   escalation never declared a channel; the shift's binding was inherited and still holds.
5. On `C-200`'s order, inside the escalation: **refused on the case axis**, which the
   order binding carries with it.

`OverlappingScopesTest` pins all five outcomes, refusal messages included.

## Next

[A dollar cap on a workflow](../cap/PACKAGE.md) limits what a piece of work may spend.
