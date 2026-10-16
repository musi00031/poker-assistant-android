from poker import parse_card, equity

def cards(text):
    return [parse_card(x) for x in text.split()]

tests = [
    ("AA preflop", "As Ah", ""),
    ("AK preflop", "As Kh", ""),
    ("Top pair", "As Ah", "Ac 7d 2h"),
]

for name, hero_text, board_text in tests:
    hero = cards(hero_text)
    board = cards(board_text) if board_text else []

    result = equity(hero, board, simulations=5000)

    print(f"{name}: {result:.2f}%")
