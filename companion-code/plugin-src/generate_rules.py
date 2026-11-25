N = 400
for i in range(N):
    meths = "\n".join(f'''    public long fee{m}(long cents) {{
        if (cents > {1000*(m+1)}L) return cents * {m+3} / 1000 + {i};
        String label = "rule-{i}-{m}-" + cents;
        return label.length() + cents / {m+7};
    }}''' for m in range(12))
    open(f"com/bank/feegrid/FeeRule{i:03d}.java", "w").write(
        f"package com.bank.feegrid;\n\n/** Generated fee rule (one per fee grid line). */\npublic class FeeRule{i:03d} {{\n{meths}\n}}\n")
