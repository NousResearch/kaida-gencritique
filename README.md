# kaida-gencritique

This is an example project demonstrating Nous Research's [Kaida library](https://github.com/NousResearch/kaida). It will generate spells for a roleplaying game using a variety of LLM models, then critique them using a different set of LLMs. It will split the spells up so that each critique has one spell from each generative model, and then record which generative model each critic model preferred.

For more accurate statistics, of course a larger run would need to be performed. This is left as an exercise to interested readers.

To run this example as written you will need to provide valid API keys in `config\auth.yaml` for OpenAI, Anthropic, and Fireworks. See `config\auth.example.yaml` for more details.