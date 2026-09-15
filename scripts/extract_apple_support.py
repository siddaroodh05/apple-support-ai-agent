import argparse
import re
import pandas as pd
 
 
MENTION_RE = re.compile(r"@\w+")
WHITESPACE_RE = re.compile(r"\s+")
 
 
def clean_text(text: str) -> str:
    """Strip leading @mentions (threading artifacts) and collapse whitespace."""
    if not isinstance(text, str):
        return ""
    text = MENTION_RE.sub("", text)
    text = WHITESPACE_RE.sub(" ", text).strip()
    return text
 
 
def build_pairs(df: pd.DataFrame, brand: str) -> pd.DataFrame:
    """Reconstruct (customer_message, support_response) pairs for one brand."""
 
    df = df.copy()
    df["tweet_id"] = df["tweet_id"].astype(str)
    df["in_response_to_tweet_id"] = df["in_response_to_tweet_id"].astype(str)
    tweets_by_id = df.set_index("tweet_id", drop=False)
 
    brand_replies = df[(df["author_id"] == brand) & (df["inbound"] == False)]
 
    rows = []
    for _, reply in brand_replies.iterrows():
        parent_id = reply["in_response_to_tweet_id"]
        if parent_id == "nan" or parent_id not in tweets_by_id.index:
            continue
 
        parent = tweets_by_id.loc[parent_id]
        if isinstance(parent, pd.DataFrame):
            parent = parent.iloc[0]
 
        if parent["inbound"] != True:
            continue
 
        customer_msg = clean_text(parent["text"])
        support_resp = clean_text(reply["text"])
 
        if not customer_msg or not support_resp:
            continue
 
        rows.append(
            {
                "tweet_id": reply["tweet_id"],
                "customer_tweet_id": parent["tweet_id"],
                "created_at": reply["created_at"],
                "customer_message": customer_msg,
                "support_response": support_resp,
            }
        )
 
    return pd.DataFrame(rows)
 
 
def main():
    parser = argparse.ArgumentParser(description="Extract AppleSupport conversation pairs")
    parser.add_argument("--input", default="data/twcs.csv", help="Path to twcs.csv")
    parser.add_argument("--brand", default="AppleSupport", help="Brand author_id to extract")
    parser.add_argument("--outdir", default="data", help="Output directory")
    parser.add_argument("--working_size", type=int, default=8000,
                         help="Number of cases for the corpus-building working pool")
    parser.add_argument("--golden_size", type=int, default=250,
                         help="Number of cases reserved for hand-labeled golden eval set")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    args = parser.parse_args()
 
    import os
    os.makedirs(args.outdir, exist_ok=True)
 
    print(f"Loading {args.input} ...")
    df = pd.read_csv(
        args.input,
        dtype={
            "tweet_id": str,
            "author_id": str,
            "in_response_to_tweet_id": str,
            "response_tweet_id": str,
        },
    )
    if df["inbound"].dtype == object:
        df["inbound"] = df["inbound"].map({"True": True, "False": False})
 
    print(f"Total rows: {len(df):,}")
    print(f"Building {args.brand} conversation pairs ...")
    pairs = build_pairs(df, args.brand)
    print(f"Reconstructed pairs: {len(pairs):,}")
 
    before = len(pairs)
    pairs = pairs.drop_duplicates(subset=["customer_message", "support_response"])
    print(f"After dedup: {len(pairs):,} (removed {before - len(pairs):,} exact duplicates)")
 
    total_needed = args.working_size + args.golden_size
    if len(pairs) < total_needed:
        raise SystemExit(
            f"Not enough pairs ({len(pairs)}) to satisfy working_size+golden_size "
            f"({total_needed}). Lower --working_size/--golden_size or pick a different brand."
        )
 
    shuffled = pairs.sample(frac=1.0, random_state=args.seed).reset_index(drop=True)
 
    golden_pool = shuffled.iloc[: args.golden_size].reset_index(drop=True)
    working_pool = shuffled.iloc[
        args.golden_size : args.golden_size + args.working_size
    ].reset_index(drop=True)
 
    working_path = f"{args.outdir}/working_pool.csv"
    golden_path = f"{args.outdir}/golden_pool.csv"
    working_pool.to_csv(working_path, index=False)
    golden_pool.to_csv(golden_path, index=False)
 
    print(f"\nSaved:")
    print(f"  {working_path}  ({len(working_pool):,} rows) -> corpus building / classification")
    print(f"  {golden_path}  ({len(golden_pool):,} rows) -> hand-label these for your golden eval set")
    print("\nThese two pools are disjoint (no shared tweet_ids) to avoid evaluation leakage.")
 
 
if __name__ == "__main__":
    main()
 