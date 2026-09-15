import argparse
import os
import re

import pandas as pd


MENTION_RE = re.compile(r"@\w+")
WHITESPACE_RE = re.compile(r"\s+")


def clean_text(text: str) -> str:
    if not isinstance(text, str):
        return ""

    text = MENTION_RE.sub("", text)
    text = WHITESPACE_RE.sub(" ", text).strip()

    return text


def build_pairs(df: pd.DataFrame, brand: str) -> pd.DataFrame:
    """Reconstruct customer -> AppleSupport conversation pairs."""

    df = df.copy()

    df["tweet_id"] = df["tweet_id"].astype(str)
    df["in_response_to_tweet_id"] = (
        df["in_response_to_tweet_id"].astype(str)
    )

    tweets_by_id = df.set_index("tweet_id", drop=False)

    brand_replies = df[
        (df["author_id"] == brand)
        & (df["inbound"] == False)
    ]

    rows = []

    for _, reply in brand_replies.iterrows():

        parent_id = reply["in_response_to_tweet_id"]

        if parent_id == "nan":
            continue

        if parent_id not in tweets_by_id.index:
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


ACCOUNT_PATTERNS = [
    r"\baccount\b",
    r"\bapple id\b",
    r"\bappleid\b",
    r"\blogin\b",
    r"\bsign in\b",
    r"\bsign-in\b",
    r"\bpassword\b",
    r"\bicloud\b",
    r"\bverification\b",
    r"\bverify\b",
    r"\bmissing\b",
    r"\blost\b",
    r"\brestore\b",
    r"\brecover\b",
    r"\bdata\b",
    r"\bcontacts\b",
    r"\bphotos\b",
    r"\bbackup\b",
]


BILLING_PATTERNS = [
    r"\bbill\b",
    r"\bbilling\b",
    r"\bcharged\b",
    r"\bcharge\b",
    r"\bpayment\b",
    r"\bpay\b",
    r"\brefund\b",
    r"\brefunds\b",
    r"\bpurchase\b",
    r"\bpurchased\b",
    r"\border\b",
    r"\breceipt\b",
    r"\bsubscription\b",
    r"\bsubscription\b",
    r"\binvoice\b",
    r"\bcredit card\b",
    r"\bdebit card\b",
    r"\bprice\b",
    r"\bcost\b",
]


THIRD_PARTY_PATTERNS = [
    r"\bwhatsapp\b",
    r"\bfacebook\b",
    r"\binstagram\b",
    r"\bgoogle\b",
    r"\bgoogle drive\b",
    r"\bgmail\b",
    r"\byoutube\b",
    r"\bspotify\b",
    r"\bnetflix\b",
    r"\bsnapchat\b",
    r"\btiktok\b",
    r"\btelegram\b",
    r"\bdiscord\b",
    r"\bslack\b",
    r"\bzoom\b",
    r"\bthird[- ]party\b",
    r"\bapp\b",
    r"\bapplication\b",
]


def pattern_score(text, patterns):
    """Count how many candidate patterns occur in the text."""

    if not isinstance(text, str):
        return 0

    text = text.lower()

    return sum(
        bool(re.search(pattern, text))
        for pattern in patterns
    )


def add_candidate_scores(df):
    """Add temporary scores used only to rank candidate cases."""

    df = df.copy()

    df["account_score"] = df["customer_message"].apply(
        lambda x: pattern_score(x, ACCOUNT_PATTERNS)
    )

    df["billing_score"] = df["customer_message"].apply(
        lambda x: pattern_score(x, BILLING_PATTERNS)
    )

    df["third_party_score"] = df["customer_message"].apply(
        lambda x: pattern_score(x, THIRD_PARTY_PATTERNS)
    )

    return df


def main():

    parser = argparse.ArgumentParser(
        description="Mine additional AppleSupport candidate cases"
    )

    parser.add_argument(
        "--input",
        default="data/twcs.csv"
    )

    parser.add_argument(
        "--existing",
        default="data/working_pool.csv",
        help="Existing 8k working pool"
    )

    parser.add_argument(
        "--outdir",
        default="data/mined_candidates"
    )

    parser.add_argument(
        "--brand",
        default="AppleSupport"
    )

    parser.add_argument(
        "--total",
        type=int,
        default=2000
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=42
    )

    args = parser.parse_args()

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
        df["inbound"] = (
            df["inbound"]
            .astype(str)
            .str.strip()
            .str.lower()
            .map({
                "true": True,
                "false": False
            })
        )

    print(f"Total TWCS rows: {len(df):,}")

    print(f"Building {args.brand} conversation pairs ...")

    pairs = build_pairs(df, args.brand)

    print(f"Reconstructed pairs: {len(pairs):,}")

    before = len(pairs)

    pairs = pairs.drop_duplicates(
        subset=[
            "customer_message",
            "support_response"
        ]
    ).reset_index(drop=True)

    print(
        f"After dedup: {len(pairs):,} "
        f"(removed {before - len(pairs):,})"
    )

    print(f"Loading existing pool: {args.existing}")

    existing = pd.read_csv(
        args.existing,
        dtype=str
    )

    if "customer_tweet_id" not in existing.columns:
        raise SystemExit(
            "Existing pool does not contain customer_tweet_id. "
            "Cannot safely remove the existing 8k."
        )

    existing_customer_ids = set(
        existing["customer_tweet_id"]
        .dropna()
        .astype(str)
    )

    print(
        f"Existing customer tweet IDs: "
        f"{len(existing_customer_ids):,}"
    )

    before = len(pairs)

    pairs = pairs[
        ~pairs["customer_tweet_id"].isin(existing_customer_ids)
    ].copy()

    print(
        f"Remaining after excluding existing 8k: "
        f"{len(pairs):,} "
        f"(removed {before - len(pairs):,})"
    )

    pairs = add_candidate_scores(pairs)

    account_candidates = pairs[
        pairs["account_score"] > 0
    ].copy()

    billing_candidates = pairs[
        pairs["billing_score"] > 0
    ].copy()

    third_party_candidates = pairs[
        pairs["third_party_score"] > 0
    ].copy()

    print("\nCandidate counts:")
    print(f"  Account/data:    {len(account_candidates):,}")
    print(f"  Billing:         {len(billing_candidates):,}")
    print(f"  Third-party:     {len(third_party_candidates):,}")

    account_n = 667
    billing_n = 667
    third_party_n = 666

    if len(account_candidates) < account_n:
        raise SystemExit(
            f"Only {len(account_candidates)} account candidates; "
            f"need {account_n}."
        )

    if len(billing_candidates) < billing_n:
        raise SystemExit(
            f"Only {len(billing_candidates)} billing candidates; "
            f"need {billing_n}."
        )

    if len(third_party_candidates) < third_party_n:
        raise SystemExit(
            f"Only {len(third_party_candidates)} third-party candidates; "
            f"need {third_party_n}."
        )

    account_selected = (
        account_candidates
        .sort_values(
            ["account_score"],
            ascending=False
        )
        .head(account_n)
    )

    billing_selected = (
        billing_candidates
        .sort_values(
            ["billing_score"],
            ascending=False
        )
        .head(billing_n)
    )

    third_party_selected = (
        third_party_candidates
        .sort_values(
            ["third_party_score"],
            ascending=False
        )
        .head(third_party_n)
    )

    output_columns = [
        "tweet_id",
        "customer_tweet_id",
        "created_at",
        "customer_message",
        "support_response",
    ]

    account_selected = account_selected[output_columns]
    billing_selected = billing_selected[output_columns]
    third_party_selected = third_party_selected[output_columns]

    account_path = (
        f"{args.outdir}/account_data_candidates.csv"
    )

    billing_path = (
        f"{args.outdir}/purchase_billing_candidates.csv"
    )

    third_party_path = (
        f"{args.outdir}/third_party_app_candidates.csv"
    )

    account_selected.to_csv(
        account_path,
        index=False
    )

    billing_selected.to_csv(
        billing_path,
        index=False
    )

    third_party_selected.to_csv(
        third_party_path,
        index=False
    )

    print("\nSaved:")
    print(
        f"  {account_path} "
        f"({len(account_selected):,})"
    )

    print(
        f"  {billing_path} "
        f"({len(billing_selected):,})"
    )

    print(
        f"  {third_party_path} "
        f"({len(third_party_selected):,})"
    )

    print(
        f"\nTotal selected: "
        f"{len(account_selected) + len(billing_selected) + len(third_party_selected):,}"
    )

    print(
        "\nNo intent column was added. "
        "These are candidate cases for LLM labeling."
    )


if __name__ == "__main__":
    main()